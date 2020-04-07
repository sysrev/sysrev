(ns sysrev.db.queries
  (:refer-clojure :exclude [find group-by])
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.string :as str]
            [honeysql.helpers :as sqlh :refer [select from where merge-where values sset
                                               join merge-join merge-left-join collify
                                               merge-select]]
            [honeysql-postgres.helpers :as sqlh-pg]
            [sysrev.db.core :as db :refer [do-query do-execute sql-field]]
            [sysrev.util :as util :refer
             [in? map-values apply-keyargs ensure-pred assert-pred]])
  (:import java.util.UUID))

;; for clj-kondo
(declare merge-join-args filter-user-permission filter-admin-user)

;;;
;;; * Query DSL
;;;

;;;
;;; ** Shared helper functions
;;;

(defn wildcard? [kw]
  (some-> kw name (str/ends-with? "*")))

(defn literal? [kw]
  (and (keyword? kw) (not (wildcard? kw))))

(defn- sql-select-map?
  "Test whether x is a honeysql map for a select query."
  [x]
  (and (map? x) (contains? x :select) (contains? x :from)))

(defn- merge-match-by
  "Returns updated honeysql map based on `m`, merging a where clause
  generated from `match-by` map."
  [m match-by]
  (merge-where m (or (some->> (seq (for [[field match-value] (seq match-by)]
                                     (if (or (sequential? match-value)
                                             (sql-select-map? match-value))
                                       [:in field match-value]
                                       [:= field match-value])))
                              (apply vector :and))
                     true)))

(s/def ::join-specifier-1
  (s/and keyword?
         #(nil? (namespace %))
         #(string? (name %))
         #(let [[join-table join-alias] (-> (name %) (str/split #"\:")) ]
            (and (string? join-table) (string? join-alias)))))

(s/def ::join-specifier-2
  (s/and keyword?
         #(nil? (namespace %))
         #(string? (name %))
         #(let [[with-table on-field] (-> (name %) (str/split #"\.")) ]
            (and (string? with-table) (string? on-field)))))

(s/def ::join-arg-single
  (s/cat :join-table-colon-alias ::join-specifier-1
         :with-table-dot-field ::join-specifier-2))

(s/def ::join-args
  (s/or :single-arg ::join-arg-single
        :multi-args (s/coll-of ::join-arg-single, :distinct true)))

(defn-spec merge-join-args map?
  "Returns updated honeysql map based on `m`, merging a join clause
  generated from `join-args` using honeysql function
  `merge-join-fn` (e.g.  merge-join, merge-left-join)."
  [m map?, merge-join-fn fn?, join-args ::join-args]
  (let [join-args (if (s/valid? ::join-arg-single join-args)
                    [join-args] join-args)]
    (reduce (fn [m [join-table-colon-alias with-table-dot-field]]
              (let [[join-table join-alias] (map keyword (-> (name join-table-colon-alias)
                                                             (str/split #"\:")))
                    [with-table on-field] (map keyword (-> (name with-table-dot-field)
                                                           (str/split #"\.")))]
                (-> m (merge-join-fn [join-table join-alias]
                                     [:= (sql-field join-alias on-field)
                                      (sql-field with-table on-field)]))))
            m join-args)))

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

(def max-sql-query-literal-values 1000)

(defn- split-match-by-value-seqs
  "Converts a `match-by` map into a sequence of match-by maps, by
  splitting into multiple queries as necessary to ensure that any
  key-value entry matching against a sequence of values contain no
  more than `max-sql-query-literal-values` literal values in a single
  SQL query."
  [match-by]
  (let [[k & more] (->> (sort-by str (keys match-by))
                        (filter #(and (sequential? (get match-by %))
                                      (> (count (get match-by %))
                                         max-sql-query-literal-values))))]
    (cond (seq more)   (throw (ex-info
                               (str "split-match-by-value-seqs: "
                                    "unable to process more than one large value sequence")
                               {:split-keys (concat [k] more), :match-by match-by}))
          k            (for [k-group (partition-all max-sql-query-literal-values
                                                    (get match-by k))]
                         (assoc match-by k k-group))
          :else        (list match-by))))

;;;
;;; ** DSL functions
;;;

;; TODO: disallow LIMIT and ORDER BY when splitting with
;;       `split-match-by-value-seqs`?
;; TODO: splitting queries like this also breaks `find-count`

(defn find
  "Runs select query on `table` filtered according to `match-by`.
  Returns a sequence of row entries; if `fields` is a keyword the
  entries will be values of the named field, otherwise the entries
  will be a map of field keyword to value.

  `table` should match format of honeysql (from ...) function.

  `fields` may be a single keyword, a sequence of keywords, or if not
  specified then all table fields will be included.

  `index-by` or `group-by` optionally provides a field keyword which
  will be used to transform the result into a map indexed by that
  field value.

  `where` optionally provides an additional honeysql where clause.

  `prepare` optionally provides a function to apply to the final
  honeysql query before running.

  The arguments `join`, `left-join`, `limit`, `order-by`, `group` add
  a clause to the query using the honeysql function of the same name.

  `return` optionally modifies the behavior and return value.
  - `:execute` (default behavior) runs the query against the connected
    database and returns the processed result.
  - `:query` returns the honeysql query map that would be run against
    the database.
  - `:string` returns an SQL string corresponding to the query."
  [table match-by &
   [fields & {:keys [index-by group-by join left-join where limit order-by group prepare return]
              :or {return :execute}
              :as opts}]]
  (assert (every? #{:index-by :group-by :join :left-join :where :limit :order-by :group
                    :prepare :return}
                  (keys opts)))
  (let [single-field (or (some->> fields (ensure-pred literal?) extract-column-name)
                         (some->> fields
                                  (ensure-pred #(and (coll? %)
                                                     (= 1 (count %))
                                                     (coll? (first %))
                                                     (= 2 (count (first %)))
                                                     (every? keyword? (first %))))
                                  first
                                  extract-column-name
                                  (ensure-pred literal?)))
        specified (some->> (cond (keyword? fields)  [fields]
                                 (empty? fields)    nil
                                 :else              fields)
                           (map extract-column-name)
                           (ensure-pred (partial every? literal?)))
        fields (cond (keyword? fields)  [fields]
                     (empty? fields)    [:*]
                     :else              (vec fields))
        select-fields (as-> (concat fields
                                    (some->> index-by (ensure-pred keyword?) (list))
                                    (some->> group-by (ensure-pred keyword?) (list)))
                          select-fields
                        (if (in? select-fields :*) [:*] select-fields)
                        (distinct select-fields))
        map-fields (cond index-by  map-values
                         group-by  (fn [f coll] (map-values (partial map f) coll))
                         :else     map)
        make-query (fn [match-by-1]
                     (-> (apply select select-fields)
                         (from table)
                         (merge-match-by match-by-1)
                         (cond-> join (merge-join-args merge-join join))
                         (cond-> left-join (merge-join-args merge-left-join left-join))
                         (cond-> where (merge-where where))
                         (cond-> limit (sqlh/limit limit))
                         (cond-> order-by (sqlh/order-by order-by))
                         (cond-> group (#(apply sqlh/group % (collify group))))
                         (cond-> prepare (prepare))))]
    (assert (in? #{0 1} (count (remove nil? [index-by group-by]))))
    (case return
      :execute  (-> (->> (split-match-by-value-seqs match-by)
                         (map make-query)
                         (mapv do-query)
                         (apply concat))
                    (cond->> limit (take limit))
                    (cond->> index-by (util/index-by index-by))
                    (cond->> group-by (clojure.core/group-by group-by))
                    (cond->> specified (map-fields #(select-keys % specified)))
                    (cond->> single-field (map-fields single-field))
                    not-empty)
      :query    (make-query match-by)
      :string   (db/to-sql-string (make-query match-by)))))

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
;;; (find :project {:project-id 3588} :name, :index-by :project-id)
;;; => {3588 "Hallmark and key characteristics mapping"}
;;;
;;; (find :project {} :name, :order-by :project-id, :limit 4, :index-by :project-id)
;;; => {1 "Novel Dose Escalation Methods and Phase I Designs: ...",
;;;     100 "EBTC - Effects on the liver as observed in experimental ...",
;;;     101 "FACTS: Factors Affecting Combination Trial Success",
;;;     102 "MnSOD in Cancer and Antioxidant Therapy"}
;;;
;;; (find [:project :p] {:p.project-id 100} :pm.user-id, :join [:project-member:pm :p.project-id])
;;; => (26 69 33 85 37 91 50 57 41 53 73 88 89 90 98 62 106 114 100 117 9 83 56 35 139 70)

(defn find-one
  "Runs `find` and returns a single result entry (or nil if none found),
  throwing an exception if the query returns more than one result."
  [table match-by & [fields & {:keys [where prepare join left-join return] :as opts
                               :or {return :execute}}]]
  (assert (every? #{:where :prepare :join :left-join :return} (keys opts)))
  (let [execute? (= return :execute)
        result (apply-keyargs find table match-by fields
                              (assoc opts :prepare #(cond-> %
                                                      prepare   (prepare)
                                                      execute?  (sqlh/limit 2))))]
    (if execute?
      (first (->> result (assert-pred {:pred #(<= (count %) 1)
                                       :message "find-one - multiple results from query"})))
      result)))

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

(defn find-count
  "Convenience function to return count of rows matching query."
  [table match-by & {:as opts}]
  (apply-keyargs find-one table match-by [[:%count.* :count]] opts))

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
  value maps. Wildcard keywords (e.g. `:*`, `:table.*`) are also
  allowed.

  `prepare` optionally provides a function to apply to the final
  honeysql query before running.

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
        single-returning? (literal? returning)
        returning (if (keyword? returning) [returning] returning)]
    (when (seq insert-values)
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
                          (first (do-execute query))))))))))

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
                         :as _opts}]]
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
                         :as _opts}]]
  (select-article-where
   project-id
   (if (or (string? article-id) (uuid? article-id))
     [:= (sql-field tname :article-uuid) article-id]
     [:= (sql-field tname :article-id) article-id])
   fields
   {:include-disabled? include-disabled?
    :tname tname}))

(defn filter-article-by-disable-flag
  [m disabled? & [{:keys [tname] :or {tname :a}} :as _opts]]
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
                       :or {include-disabled? true} :as _opts}]]
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
                                      :or {include-disabled? false} :as _opts}]]
  (cond-> (-> (apply select fields)
              (from [:label :l]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)

    project-id (merge-where [:= :project-id project-id])

    (not include-disabled?) (merge-where [:= :enabled true])))

(defn query-label-where [project-id where-clause fields]
  (-> (select-label-where project-id where-clause (or fields [:*]))
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

(defn-spec filter-user-permission map?
  [m map?, permission string?, & [not?] (s/cat :not? (s/? boolean?))]
  (let [test (db/sql-array-contains :u.permissions permission)
        test (if not? [:not test] test)]
    (merge-where m test)))

(defn-spec filter-admin-user map?
  [m map?, admin? (s/nilable boolean?)]
  (cond-> m
    (true? admin?) (filter-user-permission "admin")
    (false? admin?) (filter-user-permission "admin" true)
    (nil? admin?) (identity)))

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
      (sqlh/order-by [:pr.create-time :desc])
      (sqlh/limit 1)))

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

(defn table-exists? [table]
  (try (find table {} :*, :limit 1) true
       (catch Throwable _ false)))

(defn table-count [table]
  (find-one table {} [[:%count.* :count]]))
