(ns sysrev.entity
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db :refer [do-query do-execute with-transaction]]
            [sysrev.shared.util :as su :refer [in? map-values]]))

(s/def ::table keyword?)
(s/def ::entity ::table)
(s/def ::column keyword?)
(s/def ::field ::column)

(defonce ^:private entity-defs (atom {}))

(defn entity-columns
  "Get sequence of column name keywords for table named by keyword value
  entity. Will query database for list of columns on first invocation
  for a table; all further invocations will use cached value."
  [entity]
  (if-let [cached (get @db/entity-columns-cache entity)]
    cached
    (let [columns
          (-> (select :column-name)
              (from :information-schema.columns)
              (where [:and
                      [:= :table-schema "public"]
                      [:= :table-name (db/clj-identifier-to-sql entity)]])
              (->> do-query
                   (mapv #(-> % :column-name db/sql-identifier-to-clj keyword))))]
      (assert (not-empty columns)
              (format "columns for table %s not found in schema" (pr-str entity)))
      (swap! db/entity-columns-cache assoc entity columns)
      columns)))
;;;
(s/fdef entity-columns
  :args (s/cat :entity ::entity)
  :ret (s/coll-of ::column))

(defn entity-custom-fields
  "Get sequence of custom field name keywords for entity."
  [entity]
  (some-> @entity-defs (get entity) :values keys sort vec))
;;;
(s/fdef entity-custom-fields
  :args (s/cat :entity ::entity)
  :ret (s/coll-of ::field))

(defn entity-fields [entity]
  (let [columns (entity-columns entity)
        custom-fields (entity-custom-fields entity)
        conflicts (set/intersection (set columns) (set custom-fields))]
    (assert (empty? conflicts)
            (str "custom entity fields must not conflict with column names"
                 (format " [%s: %s]" (name entity) (pr-str (seq conflicts)))))
    (vec (concat columns custom-fields))))
;;;
(s/fdef entity-fields
  :args (s/cat :entity ::entity)
  :ret (s/coll-of ::field))

(s/def ::primary-key (s/or :column ::column
                           :multi (s/coll-of ::column)))

(defn update-entity-def [entity update-fn]
  (swap! entity-defs update entity update-fn)
  (get @entity-defs entity))

(defn def-entity
  "Creates base definition for a database entity."
  [entity {:keys [primary-key] :as values}]
  (assert (s/valid? ::entity entity))
  (assert (s/valid? ::primary-key primary-key))
  (update-entity-def entity #(merge % values)))

(defn def-entity-value
  "Defines a custom value belonging to instances of entity, allowing the
  value to be queried on entity in the same manner as columns in
  entity's table. field must be a keyword identifier for the value,
  and must not conflict with column names in the table. value-fn
  should a function of one argument taking a primary key value for the
  entity."
  [entity field value-fn]
  (assert (s/valid? ::entity entity))
  (assert (s/valid? ::field field))
  (assert (s/valid? ifn? value-fn))
  (update-entity-def entity #(assoc-in % [:values field] value-fn)))

(defn custom-value-fn [entity field]
  "Gets value function for a custom entity field."
  (get-in @entity-defs [entity :values field]))

;; TODO: support user-defined default list of columns to include
(defn- get-with-custom [entity primary-key custom-fields]
  "Gets value map for an instance of entity using primary-key, including
  values for the specified custom fields."
  (let [def (get @entity-defs entity)
        pkey-field (get def :primary-key)
        ;; TODO: support field-combination primary keys
        _ (assert (s/valid? keyword? pkey-field))
        columns (entity-columns entity)
        custom (->> (entity-custom-fields entity)
                    (filter (in? custom-fields)))
        result (-> (select :*)
                   (from entity)
                   (where [:= pkey-field primary-key])
                   (limit 5)
                   do-query)]
    (assert (<= (count result) 1)
            "get-with-custom: got multiple results (should be 0 or 1)")
    (when (not-empty result)
      (merge (first result)
             (->> custom
                  (map (fn [field]
                         {field ((custom-value-fn entity field) primary-key)}))
                  (apply merge))))))

(defn get-all
  "Gets value map for an instance of entity using primary-key, including
  all custom fields defined for entity. without is an optional
  sequence of fields to exclude from query."
  [entity primary-key & {:keys [without]}]
  (as-> (get-with-custom entity primary-key
                         (->> (entity-custom-fields entity)
                              (remove (in? without))))
      value
    ;; TODO: exclude 'without' columns from db query
    (apply dissoc value without)))
