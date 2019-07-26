(ns sysrev.db.queries
  (:refer-clojure :exclude [find])
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update delete]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :as sqlh-pg :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db :refer [do-query do-execute sql-field]]
            [sysrev.shared.util :as sutil :refer
             [in? ->map-with-key or-default map-values apply-keyargs
              ensure-pred assert-pred]])
  (:import java.util.UUID))

;;;
;;; * Query DSL
;;;

;;;
;;; ** Shared helper functions
;;;

(defn- merge-match-by
  "Returns updated honeysql map based on `m`, merging a where clause
  generated from `match-by` map."
  [m match-by]
  (merge-where m (or (some->> (seq (for [[field match-value] (seq match-by)]
                                     (if (sequential? match-value)
                                       [:in field match-value]
                                       [:= field match-value])))
                              (apply vector :and))
                     true)))

(defn- merge-join-args
  "Returns updated honeysql map based on `m`, merging a join clause
  generated from `join-args` using honeysql function
  `merge-join-fn` (e.g.  merge-join, merge-left-join)."
  [m merge-join-fn join-args]
  (reduce (fn [m [with-table join-table join-alias on-field]]
            (merge-join-fn m
                           [join-table join-alias]
                           [:= (sql-field join-alias on-field)
                            (sql-field with-table on-field)]))
          m join-args))

(defn- extract-column-name
  "Extracts an unqualified column name keyword from honeysql keyword `k`
  by dropping any preceding '<table>.' portion. If `k` is a
  two-element keyword sequence (following format of honeysql
  select), returns the second keyword. Returns nil if `k` does not
  seem to represent a column; presence of '%' indicates that `k`
  represents an sql function call rather than a column."
  [k]
  (if (and (sequential? k) (= (count k) 2) (every? keyword? k))
    (ensure-pred keyword? (second k))
    (some-> (ensure-pred keyword? k)
            name
            ((ensure-pred (fn [s] (not (str/includes? s "%")))))
            (str/split #"\.")
            last
            keyword)))

;;;
;;; ** DSL functions
;;;

(defn find
  "Runs select query on `table` filtered according to `match-by`.
  Returns a sequence of row entries; if `fields` is a keyword the
  entries will be values of the named field, otherwise the entries
  will be a map of field keyword to value.

  `table` should match format of honeysql (from ...) function.

  `fields` may be a single keyword, a sequence of keywords, or if not
  specified then all table fields will be included.

  `index` or `group` optionally provides a field keyword which will be
  used to transform the result into a map indexed by that field value.

  `where` optionally provides an additional honeysql where clause.

  `prepare` optionally provides a function to apply to the final
  honeysql query before running.

  The arguments `join`, `left-join`, `limit` add a clause to the query
  using the honeysql function of the same name.

  `return` optionally modifies the behavior and return value.
  - `:execute` (default behavior) runs the query against the connected
    database and returns the processed result.
  - `:query` returns the honeysql query map that would be run against
    the database.
  - `:string` returns an SQL string corresponding to the query."
  [table match-by & [fields & {:keys [index group join left-join where limit prepare return]
                               :or {return :execute}
                               :as opts}]]
  (assert (every? #{:index :group :join :left-join :where :limit :prepare :return}
                  (keys opts)))
  (let [wildcard? #(some-> % name (str/ends-with? "*"))
        literal? #(and (keyword? %) (not (wildcard? %)))
        single-field (some->> fields
                              (ensure-pred literal?)
                              (extract-column-name))
        specified (some->> (cond (keyword? fields)  [fields]
                                 (empty? fields)    nil
                                 :else              fields)
                           (map extract-column-name)
                           (ensure-pred (partial every? literal?)))
        fields (cond (keyword? fields)  [fields]
                     (empty? fields)    [:*]
                     :else              (vec fields))
        select-fields (as-> (concat fields
                                    #_ (keys match-by)
                                    (some->> index (ensure-pred keyword?) (list))
                                    (some->> group (ensure-pred keyword?) (list)))
                          select-fields
                        (if (in? select-fields :*) [:*] select-fields)
                        (distinct select-fields))
        map-fields (cond index  map-values
                         group  (fn [f coll] (map-values (partial map f) coll))
                         :else  map)]
    (assert (in? #{0 1} (count (remove nil? [index group]))))
    (-> (apply select select-fields)
        (from table)
        (merge-match-by match-by)
        (cond-> join (merge-join-args merge-join join))
        (cond-> left-join (merge-join-args merge-left-join left-join))
        (cond-> where (merge-where where))
        (cond-> limit (sqlh/limit limit))
        (cond-> prepare (prepare))
        ((fn [query]
           (case return
             :query       query
             :string      (db/to-sql-string query)
             :execute     (-> (do-query query)
                              (cond->> index (->map-with-key index))
                              (cond->> group (group-by group))
                              (cond->> specified (map-fields #(select-keys % specified)))
                              (cond->> single-field (map-fields single-field))
                              not-empty)))))))

;;; Examples:
;;;
;;; (find :web-user {:email "browser+test@insilica.co"} :user-id)
;;; => (95)
;;;
;;; (find :project {:project-id 3588} :name)
;;; => ("Hallmark and key characteristics mapping")
;;;
;;; (find :project-member {:project-id 100} :user-id, :limit 10)
;;; => (70 35 139 95 56 83 9 117 100 114)
;;;
;;; (find :project {:project-id 3588})
;;; => [{:project-id 3588,
;;;      :name "Hallmark and key characteristics mapping",
;;;      :enabled true,
;;;      ...}]
;;;
;;; (find :project {:project-id 3588} [:project-id :name])
;;; => ({:project-id 3588, :name "Hallmark and key characteristics mapping"})
;;;
;;; (find :project {:project-id 3588} :name, :index :project-id)
;;; => {3588 "Hallmark and key characteristics mapping"}
;;;
;;; (find :project {} :name, :index :project-id, :limit 4)
;;; => {101 "FACTS: Factors Affecting Combination Trial Success",
;;;     6064 "test",
;;;     102 "MnSOD in Cancer and Antioxidant Therapy",
;;;     106 "FACTS Data Extraction"}

(defn find-one
  "Runs `find` and returns a single result entry (or nil if none found),
  throwing an exception if the query returns more than one result."
  [table match-by & [fields & {:keys [where prepare join left-join] :as opts}]]
  (assert (every? #{:where :prepare :join :left-join} (keys opts)))
  (first (->> (apply-keyargs find table match-by fields
                             (assoc opts :prepare #(-> (cond-> % prepare (prepare))
                                                       (limit 2))))
              (assert-pred {:pred #(<= (count %) 1)
                            :message "find-one - multiple results from query"})) ))

;;; Examples:
;;;
;;; (find-one :web-user {:user-id 70} [:email :settings])
;;; => {:email "jeff@insilica.co", :settings {:ui-theme "Dark"}}
;;;
;;; (find-one :web-user {:user-id 70} :email)
;;; => "jeff@insilica.co"
;;;
;;; (find-one :web-user {:email "jeff@insilica.co"} :user-id)
;;; => 70
;;;
;;; (:verified (find-one :web-user {:user-id 70}))
;;; => true

(defn exists
  "Runs (find ... :return :query) and wraps result in [:exists ...]."
  [table match-by & {:keys [join left-join where prepare] :as opts}]
  (assert (every? #{:join :left-join :where :prepare} (keys opts)))
  [:exists (apply-keyargs find table match-by :*
                          (assoc opts :return :query))])

(defn not-exists
  "Runs (find ... :return :query) and wraps result in [:not [:exists ...]."
  [table match-by & {:keys [join left-join where prepare] :as opts}]
  (assert (every? #{:join :left-join :where :prepare} (keys opts)))
  [:not (apply-keyargs exists table match-by opts)])

(defn create
  "Runs insert query on `table` using sequence of value maps `insert-values`.
  Returns a count of rows updated.

  `insert-values` may also be a map and will be treated as a single
  entry; if a `returning` argument is given, this will give a return
  value of one entry rather than a sequence of entries.

  `table` should match format of honeysql (from ...) function.

  `returning` optionally takes a sequence of field keywords to use as
  arguments to a Postgres-specific returning clause. `returning` may
  also be a single keyword; in this case the return value will be a
  sequence of values for this field rather than a sequence of field
  value maps.

  `prepare` optionally provides a function to apply to the final honeysql
  query before running.

  `return` optionally modifies the behavior and return value.
  - `:execute` (default behavior) runs the query against the connected
    database and returns the processed result.
  - `:query` returns the honeysql query map that would be run against
    the database.
  - `:string` returns an SQL string corresponding to the query."
  [table insert-values & {:keys [returning prepare return]
                          :or {return :execute}
                          :as opts}]
  (assert (every? #{:returning :prepare :return} (keys opts)))
  (let [single-value? (map? insert-values)
        insert-values (if (map? insert-values) [insert-values] insert-values)
        single-returning? (and (keyword? returning) (not= returning :*))
        returning (if (keyword? returning) [returning] returning)]
    (-> (sqlh/insert-into table)
        (values insert-values)
        (cond-> returning (#(apply sqlh-pg/returning % returning)))
        (cond-> prepare (prepare))
        ((fn [query]
           (case return
             :query   query
             :string  (db/to-sql-string query)
             :execute (if returning
                        (cond->> (do-query query)
                          single-returning? (map (first returning))
                          single-value? first)
                        (first (do-execute query)))))))))

(defn modify
  "Runs update query on `table` filtered according to `match-by`.
  Returns a count of rows updated.

  `table` should match format of honeysql (from ...) function.

  `where` optionally provides an additional honeysql where clause.

  `returning` optionally takes a sequence of field keywords to use as
  arguments to a Postgres-specific returning clause. `returning` may
  also be a single keyword; in this case the return value will be a
  sequence of values for this field rather than a sequence of field
  value maps.

  `prepare` optionally provides a function to apply to the final honeysql
  query before running.

  The arguments `join`, `left-join` add a clause to the query using
  the honeysql function of the same name.

  `return` optionally modifies the behavior and return value.
  - `:execute` (default behavior) runs the query against the connected
    database and returns the processed result.
  - `:query` returns the honeysql query map that would be run against
    the database.
  - `:string` returns an SQL string corresponding to the query."
  [table match-by set-values & {:keys [where returning prepare join left-join return]
                                :or {return :execute}
                                :as opts}]
  (assert (every? #{:where :returning :prepare :join :left-join :return} (keys opts)))
  (let [single-returning? (and (keyword? returning) (not= returning :*))
        returning (if (keyword? returning) [returning] returning)]
    (-> (sqlh/update table)
        (merge-match-by match-by)
        (sset set-values)
        (cond-> join (merge-join-args merge-join join))
        (cond-> left-join (merge-join-args merge-left-join left-join))
        (cond-> where (merge-where where))
        (cond-> returning (#(apply sqlh-pg/returning % returning)))
        (cond-> prepare (prepare))
        ((fn [query]
           (case return
             :query   query
             :string  (db/to-sql-string query)
             :execute (if returning
                        (cond->> (do-query query)
                          single-returning? (map (first returning)))
                        (first (do-execute query)))))))))

(defn delete
  "Runs delete query on `table` filtered according to `match-by`.
  Returns a count of rows deleted.

  `table` should match format of honeysql (from ...) function.

  `where` optionally provides an additional honeysql where clause.

  `prepare` optionally provides a function to apply to the final honeysql
  query before running.

  The arguments `join`, `left-join` add a clause to the query
  using the honeysql function of the same name.

  `return` optionally modifies the behavior and return value.
  - `:execute` (default behavior) runs the query against the connected
    database and returns the processed result.
  - `:query` returns the honeysql query map that would be run against
    the database.
  - `:string` returns an SQL string corresponding to the query."
  [table match-by & {:keys [where prepare join left-join return]
                     :or {return :execute}
                     :as opts}]
  (assert (every? #{:where :prepare :join :left-join :return} (keys opts)))
  (-> (sqlh/delete-from table)
      (merge-match-by match-by)
      (cond-> join (merge-join-args merge-join join))
      (cond-> left-join (merge-join-args merge-left-join left-join))
      (cond-> where (merge-where where))
      (cond-> prepare (prepare))
      ((fn [query]
         (case return
           :query   query
           :string  (db/to-sql-string query)
           :execute (first (do-execute query)))))))

;;;
;;; * Article queries
;;;

(defn select-project-articles
  "Constructs a honeysql query to select the articles in project-id.

   Defaults to excluding any disabled articles.

   Set option include-disabled? as true to include all disabled articles.

   Set option include-disabled-source? as true to exclude only articles
   which are disabled by an article-flag entry.

   Only one of [include-disabled? include-disabled-source?] should be set."
  [project-id fields & [{:keys [include-disabled? tname include-disabled-source?]
                         :or {tname :a}
                         :as opts}]]
  (letfn [(a [field] (sql-field tname field))]
    (find [:article tname]
          (merge (when project-id
                   {(a :project-id) project-id})
                 (when (and (not include-disabled?)
                            (not include-disabled-source?))
                   {(a :enabled) true}))
          fields
          :where (when include-disabled-source?
                   (not-exists [:article-flag :af-1]
                               {:af-1.article-id (a :article-id), :af-1.disable true}))
          :return :query)))

(defn select-article-where [project-id where-clause fields & [{:keys [include-disabled? tname] :as opts}]]
  (cond-> (select-project-articles project-id fields opts)
    (not (in? #{true nil} where-clause))
    (merge-where where-clause)))

(defn select-article-by-id
  [article-id fields & [{:keys [include-disabled? tname project-id]
                         :or {include-disabled? true
                              tname :a
                              project-id nil}
                         :as opts}]]
  (select-article-where
   project-id
   (if (or (string? article-id) (uuid? article-id))
     [:= (sql-field tname :article-uuid) article-id]
     [:= (sql-field tname :article-id) article-id])
   fields
   {:include-disabled? include-disabled?
    :tname tname}))

(defn filter-article-by-disable-flag
  [m disabled? & [{:keys [tname] :or {tname :a}}
                  :as opts]]
  (let [exists
        [:exists
         (-> (select :*)
             (from [:article-flag :af-filter])
             (where [:and
                     [:= :af-filter.disable true]
                     [:=
                      :af-filter.article-id
                      (sql-field tname :article-id)]]))]]
    (cond-> m
      disabled? (merge-where exists)
      (not disabled?) (merge-where [:not exists]))))

(defn join-article-flags [m]
  (merge-join m [:article-flag :aflag] [:= :aflag.article-id :a.article-id]))

(defn query-article-by-id [article-id fields & [opts]]
  (-> (select-article-by-id article-id fields opts)
      do-query first))

;;;
;;; * Label queries
;;;

(defn select-label-by-id
  [label-id fields & [{:keys [include-disabled?]
                       :or {include-disabled? true}
                       :as opts}]]
  (assert (or (in? [UUID String] (type label-id))
              (integer? label-id)))
  (cond->
      (-> (apply select fields)
          (from [:label :l]))
    (integer? label-id)
    (merge-where [:= :label-id-local label-id])
    (not (integer? label-id))
    (merge-where [:= :label-id label-id])
    (not include-disabled?)
    (merge-where [:= :enabled true])))

(defn select-label-where
  [project-id where-clause fields & [{:keys [include-disabled?]
                                      :or {include-disabled? false}
                                      :as opts}]]
  (cond->
      (-> (apply select fields)
          (from [:label :l]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)
    project-id (merge-where [:= :project-id project-id])
    (not include-disabled?) (merge-where [:= :enabled true])))

(defn query-label-where [project-id where-clause fields]
  (-> (select-label-where project-id where-clause [:*])
      do-query first))

(defn join-article-labels [m & [{:keys [tname-a tname-al]
                                 :or {tname-a :a
                                      tname-al :al}}]]
  (-> m (merge-join [:article-label tname-al]
                    [:=
                     (sql-field tname-al :article-id)
                     (sql-field tname-a :article-id)])))

(defn join-article-source [m & [{:keys [tname-a tname-asrc]
                                 :or {tname-a :a tname-asrc :asrc}}]]
  (-> m (merge-join [:article-source tname-asrc]
                    [:=
                     (sql-field tname-asrc :article-id)
                     (sql-field tname-a :article-id)])))

(defn join-article-label-defs [m]
  (-> m (merge-join [:label :l]
                    [:= :l.label-id :al.label-id])))

(defn label-confirmed-test [confirmed?]
  (case confirmed?
    true [:!= :confirm-time nil]
    false [:= :confirm-time nil]
    true))

(defn filter-valid-article-label [m confirmed?]
  (-> m (merge-where [:and
                      (label-confirmed-test confirmed?)
                      [:!= :al.answer nil]
                      [:!= :al.answer (db/to-jsonb nil)]
                      [:!= :al.answer (db/to-jsonb [])]
                      #_ [:!= :al.answer (db/to-jsonb {})]])))

(defn filter-label-user [m user-id]
  (-> m (merge-where [:= :al.user-id user-id])))

(defn select-project-article-labels [project-id confirmed? fields]
  (-> (select-project-articles project-id fields)
      (join-article-labels)
      (join-article-label-defs)
      (filter-valid-article-label confirmed?)))

(defn select-user-article-labels [user-id article-id confirmed? fields]
  (-> (apply select fields)
      (from [:article-label :al])
      (where [:and
              [:= :al.user-id user-id]
              [:= :al.article-id article-id]])
      (filter-valid-article-label confirmed?)))

;;;
;;; * Project queries
;;;

(defn select-project-where [where-clause fields]
  (cond-> (-> (apply select fields) (from [:project :p]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)))

(defn query-project-by-id [project-id fields]
  (-> (select-project-where [:= :p.project-id project-id] fields)
      do-query first))

;;;
;;; * Keyword queries
;;;

(defn select-project-keywords [project-id fields]
  (-> (apply select fields)
      (from [:project-keyword :pkw])
      (where [:= :pkw.project-id project-id])))

;;;
;;; * User queries
;;;

(defn select-project-members [project-id fields]
  (-> (apply select fields)
      (from [:project-member :m])
      (join [:web-user :u]
            [:= :u.user-id :m.user-id])
      (where [:= :m.project-id project-id])))

(defn join-users [m user-id]
  (merge-join m [:web-user :u] [:= :u.user-id user-id]))

(defn join-user-member-entries [m project-id]
  (-> (merge-join m [:project-member :m] [:= :m.user-id :u.user-id])
      (merge-where [:= :m.project-id project-id])))

(defn filter-user-permission [m permission & [not?]]
  (let [test (db/sql-array-contains :u.permissions permission)
        test (if not? [:not test] test)]
    (merge-where m test)))
;;;
(s/fdef filter-user-permission
  :args (s/cat :m ::sc/honeysql
               :permission string?
               :not? (s/? boolean?))
  :ret ::sc/honeysql)

(defn filter-admin-user [m admin?]
  (cond-> m
    (true? admin?) (filter-user-permission "admin")
    (false? admin?) (filter-user-permission "admin" true)
    (nil? admin?) (identity)))
;;;
(s/fdef filter-admin-user
  :args (s/cat :m ::sc/honeysql
               :admin? (s/nilable boolean?))
  :ret ::sc/honeysql)

;;;
;;; * Label prediction queries
;;;

(defn select-predict-run-where [where-clause fields]
  (cond-> (-> (apply select fields)
              (from [:predict-run :pr]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)))

(defn query-predict-run-by-id [predict-run-id fields]
  (-> (select-predict-run-where
       [:= :predict-run-id predict-run-id] fields)
      do-query first))

(defn join-article-predict-values [m & [predict-run-id]]
  (if-not predict-run-id m
          (cond-> m
            true (merge-left-join [:label-predicts :lp]
                                  [:= :lp.article-id :a.article-id])
            predict-run-id (merge-where [:= :lp.predict-run-id predict-run-id])
            true (merge-where [:or
                               [:= :lp.stage 1]
                               [:= :lp.stage nil]]))))

(defn with-article-predict-score [m predict-run-id]
  (if-not predict-run-id m
          (-> m
              (join-article-predict-values predict-run-id)
              (merge-left-join [:label :l]
                               [:= :l.label-id :lp.label-id])
              (merge-where [:= :l.name "overall include"])
              (merge-select [:lp.val :score]))))

(defn select-latest-predict-run [fields]
  (-> (apply select fields)
      (from [:predict-run :pr])
      (order-by [:pr.create-time :desc])
      (limit 1)))

(defn project-latest-predict-run-id
  "Gets the most recent predict-run ID for a project."
  [project-id]
  (db/with-project-cache project-id [:predict :latest-predict-run-id]
    (-> (select-latest-predict-run [:predict-run-id])
        (merge-where [:= :project-id project-id])
        do-query first :predict-run-id)))

(defn article-latest-predict-run-id
  "Gets the most recent predict-run ID for the project of an article."
  [article-id]
  (-> (select-latest-predict-run [:predict-run-id])
      (merge-join [:project :p]
                  [:= :p.project-id :pr.project-id])
      (merge-join [:article :a]
                  [:= :a.project-id :p.project-id])
      (merge-where (if (or (string? article-id) (uuid? article-id))
                     [:= :a.article-uuid article-id]
                     [:= :a.article-id article-id]))
      do-query first :predict-run-id))

;;;
;;; * Article note queries
;;;

(defn with-project-note [m & [note-name]]
  (cond-> m
    true (merge-join [:project-note :pn]
                     [:= :pn.project-id :p.project-id])
    note-name (merge-where [:= :pn.name note-name])))

(defn with-article-note [m & [note-name user-id]]
  (cond->
      (-> m
          (merge-join [:article-note :an]
                      [:= :an.article-id :a.article-id])
          (merge-join [:project-note :pn]
                      [:= :pn.project-note-id :an.project-note-id]))
    note-name (merge-where [:= :pn.name note-name])
    user-id (merge-where [:= :an.user-id user-id])))

;;;
;;; * Utility functions
;;;

(defn query-multiple-by-id
  "Runs query to select rows from table where id-field is any of id-values.
  Allows for unlimited count of id-values by partitioning values into
  groups and running multiple select queries."
  [table fields id-field id-values & {:keys [where prepare]}]
  (apply concat (for [id-group (->> (if (sequential? id-values) id-values [id-values])
                                    (partition-all 200))]
                  (when (seq id-group)
                    (-> (apply select fields) (from table)
                        (sqlh/where [:in id-field (vec id-group)])
                        (#(if where (merge-where % where) %))
                        (#(if prepare (prepare %) %))
                        do-query)))))
