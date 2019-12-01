(ns metabase.driver.cubejs
  "Cube.js REST API driver."
  (:require [clojure.tools.logging :as log]
            [metabase.driver :as driver]
            [metabase.mbql.util :as mbql.util]
            [metabase.query-processor.store :as qp.store]
            [metabase.models.metric :as metric :refer [Metric]]
            [metabase.driver.cubejs.utils :as cube.utils]
            [metabase.driver.cubejs.query-processor :as cubejs.qp]
            [toucan.db :as db]))


;; In case of a full table query the granularity is :default so we have to change it manually.
(def default-cubejs-granularity
  :day)

(defn- mbql-granularity->cubejs-granularity
  "Set the correct granularity for the Cube.js query."
  [granularity]
  (if (= granularity :default) default-cubejs-granularity granularity))

(defn- measure-in-metrics?
  "Checks is the given measure already in the metrics."
  [measure metrics]
  (some (fn [metric] (= (:name measure) (:name metric))) metrics))

(defn- get-cubes
  "Get all the cubes from the Cube.js REST API."
  [database]
  (let [resp   (cube.utils/make-request "v1/meta" nil database)
        body   (:body resp)]
    (:cubes body)))

(defn- process-fields
  "Returns the processed fields from the 'measure' or 'dimension' block. Description must be 'measure' or 'dimension'."
  [fields type]
  (for [field fields]
    (merge
     {:name          (:name field)
      :database-type (:type field)
      :field-comment type
      :description   (:description field)
      :base-type     (cube.utils/json-type->base-type (keyword (:type field)))}
     (if (= (:type field) cube.utils/cubejs-time->metabase-type) {:special-type :type/CreationTime}))))

(defn- get-field
  "Returns the name and the type for the given field ID."
  [field]
  (let [field (if (= (first field) :field-id) (qp.store/field (second field)))
        name  (:name field)
        type  (:description field)]
    (list name type)))

(defn- get-fields-with-type
  "Returns all the field names with the given type from the given list."
  [fields type]
  (let [filtered (filter (fn [x] (= (second x) type)) fields)
        result   (for [f filtered] (first f))]
    result))

(defn- get-field-names-by-type
  "Return the name of the given list of field IDs filtered by the type 'measure or dimension),"
  [field-ids type]
  (let [fields   (for [id field-ids] (qp.store/field id))
        filtered (filter #(= (:description %) type) fields)
        names    (for [field filtered] (:name field))]
    names))

(defn- transform-orderby
  "Transform the MBQL order by to a Cube.js order."
  [query]
  (let [order-by (:order-by query)]
    ;; Iterate over the order-by fields.
    (into {} (for [[direction [field-type value]] order-by] (
                                                             ;; Get the name of the field based on its type..
                                                             let [fieldname (case field-type
                                                                              :field-id       (first (get-field [field-type value]))
                                                                              :aggregation    (nth (mbql.util/match query [:aggregation-options _ {:display-name name}] name) value)
                                                                              :datetime-field (first (get-field value))
                                                                              nil)]
                                                              {fieldname direction})))))

(defn- get-measures
  "Get the measure fields from a MBQL query."
  [query]
  (concat
    ;; The simplest case is there are a list of fields in the query.
   (let [field-ids   (mbql.util/match (:fields query) [:field-id id] id)
         field-names (get-field-names-by-type field-ids "measure")]
     field-names)
   ;; Another case if we use metrics so Metabase creates the query for us.
   (mbql.util/match query [:aggregation-options _ {:display-name name}] name)))

(defn- get-dimensions
  "Get the dimension fields from a MBQL query."
  [query]
  (concat
    ;; The simplest case is there are a list of fields in the query.
   (let [fields       (remove nil? (map get-field (:fields query)))
         aggregations (get-fields-with-type fields "dimension")]
     aggregations)
    ;; Another case if we use metrics so Metabase creates the query for us.
   (let [dimensions (remove nil? (map first (map get-field (:breakout query))))]
     (if dimensions (set dimensions)))))

(defn- get-time-dimensions
  "Get the time dimensions from the MBQL query."
  [query]
  (let [fields       (mbql.util/match query [:datetime-field [:field-id id] gran] (list id (mbql-granularity->cubejs-granularity gran)))
        named-fields (for [field fields] (list (:name (qp.store/field (first field))) (second field)))]
    (if named-fields (set named-fields))))

(defn- mbql->cubejs
  "Build a valid Cube.js query from the generated MBQL."
  [query]
  (let [measures        (get-measures query)
        dimensions      (get-dimensions query)
        time-dimensions (get-time-dimensions query)
        order-by        (transform-orderby query)
        limit           (:limit query)]
    (merge
     (if (empty? measures) nil {:measures measures})
     (if (empty? dimensions) nil {:dimensions dimensions})
     (if (empty? time-dimensions) nil {:timeDimensions (for [td time-dimensions] {:dimension (first td), :granularity (second td)})})
     (if (empty? order-by) nil {:order order-by})
     (if limit {:limit limit}))))

;;; Implement Metabase driver functions.

(driver/register! :cubejs)

(defmethod driver/supports? [:cubejs :basic-aggregations] [_ _] false)

(defmethod driver/can-connect? :cubejs [_ details]
  (if (nil? (get-cubes {:details details})) false true))

(defmethod driver/describe-database :cubejs [_ database]
  {:tables (set (for [cube (get-cubes database)]
                  {:name   (:name cube)
                   :schema (:schema cube)}))})

(defmethod driver/describe-table :cubejs [_ database table]
  (let [cubes      (get-cubes database)
        cube       (first (filter (comp (set (list (:name table))) :name) cubes))
        measures   (process-fields (:measures cube) "measure")
        dimensions (process-fields (:dimensions cube) "dimension")
        metrics    (metric/retrieve-metrics (:id table) :all)]
    (doseq [measure measures]
      (if-not (measure-in-metrics? measure metrics)
        (db/insert! Metric
                    :table_id    (:id table)
                    :creator_id  1 ; Use the first (creator, admin) user at the moment.) Any better solution?
                    :name        (:name measure)
                    :description (:description measure)
                    :definition  {:source-table (:id table)
                                  :aggregation  [[:count]]})))
    {:name   (:name cube)
     :schema (:schema cube)
     ; Segments are currently unsupported.
     ;; Remove the description key from the fields then create a set.
     :fields (set (map #(dissoc % :description) (concat measures dimensions)))}))

(defmethod driver/mbql->native :cubejs [_ query]
  (log/debug "MBQL:" query)
  (let [base-query  (mbql->cubejs (:query query))]
    {:query        base-query
     :aggregation? (if-not (empty? (:aggregation (:query query))) true)
     :mbql?        true}))

(defmethod driver/execute-query :cubejs [_ {native-query :native}]
  (cubejs.qp/execute-http-request native-query))