(ns sysrev.db.queries
  (:refer-clojure :exclude [find group-by])
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.string :as str]
            [honeysql.helpers :as sqlh :refer [select from where merge-where values sset
                                               join merge-join merge-left-join collify]]
            [honeysql-postgres.helpers :as sqlh-pg]
            [sysrev.db.core :as db :refer [do-query do-execute sql-field]]
            [sysrev.util :as util :refer [in? map-values apply-keyargs when-test
                                          assert-pred opt-keys ensure-vector]]))

;; for clj-kondo
(declare merge-join-args filter-user-permission filter-admin-user
         find find-one find-count exists not-exists create modify delete)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Query DSL - spec definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn no-spaces? [s]
  (when (or (string? s) (keyword? s))
    (let [s (name s)]
      (not (str/includes? s " ")))))

(defn dotted-column? [s]
  (when (or (string? s) (keyword? s))
    (let [s (name s)]
      (= 2 (count (str/split s #"\."))))))

;;; query dsl values
(s/def ::keyword-id (s/and keyword? no-spaces?))
(s/def ::string-id (s/and string? no-spaces?))
(s/def ::simple-id (s/or :keyword ::keyword-id, :string ::string-id))
(s/def ::aliased-id (s/coll-of ::simple-id, :kind vector?, :count 2))
(s/def ::named-id (s/or :simple ::simple-id, :aliased ::aliased-id))
(s/def ::table-column (s/or :keyword (s/and ::keyword-id dotted-column?)
                            :string  (s/and ::string-id  dotted-column?)))

;;; join syntax
(s/def ::join-single (s/or :simple (s/cat :table ::named-id, :on-field ::table-column)
                           :full   (s/cat :table ::named-id, :cond ::db/cond)))
(s/def ::join-spec (s/or :single ::join-single
                         :multi  (s/coll-of ::join-single, :distinct true)))
#_ (s/conform ::join-spec [[:project-member :pm] :u.user-id])

;;; top-level function args
(s/def ::table ::named-id)
(s/def ::match-by (s/nilable (s/map-of any? any?)))
(s/def ::fields (s/or :single ::named-id
                      :multi (s/coll-of ::named-id)))
(s/def ::index-by ifn?)
(s/def ::group-by ifn?)
(s/def ::join ::join-spec)
(s/def ::left-join ::join-spec)
(s/def ::where any?)
(s/def ::limit any?)
(s/def ::order-by any?)
(s/def ::group any?)
(s/def ::prepare ifn?)
(s/def ::return (s/and keyword? (in? #{:execute :query :string})))
(s/def ::insert-values-single map?)
(s/def ::insert-values-multi (s/coll-of ::insert-values-single))
(s/def ::insert-values (s/or :single ::insert-values-single
                             :multi  ::insert-values-multi))
(s/def ::returning (s/or :single ::keyword-id
                         :multi  (s/coll-of ::keyword-id)))
(s/def ::set-values map?)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- Query DSL - spec definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Query DSL - helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

(defn- read-named-id [id]
  (->> (cond (s/valid? ::simple-id id)   [id id]
             (s/valid? ::aliased-id id)  id)
       (mapv keyword)))

(defn- read-table-column [id]
  (when (s/valid? ::table-column id)
    (mapv keyword (str/split (name id) #"\."))))

(defn-spec ^:private merge-join-args map?
  "Returns updated honeysql map based on `m`, merging a join clause
  generated from `join-spec` using honeysql function
  `merge-join-fn` (e.g. merge-join, merge-left-join)."
  [m map?, merge-join-fn fn?, join-spec ::join-spec]
  (reduce (fn [m j]
            (let [[join-table arg1] j
                  [_ join-alias] (read-named-id join-table)]
              (case (first (s/conform ::join-single j))
                :simple (let [[by-table by-field] (read-table-column arg1)]
                          (merge-join-fn m join-table
                                         [:= (sql-field join-alias by-field)
                                          (sql-field by-table by-field)]))
                :full (merge-join-fn m join-table arg1))))
          m (if (= :single (first (s/conform ::join-spec join-spec)))
              [join-spec] join-spec)))

(defn- extract-column-name
  "Extracts an unqualified column name keyword from honeysql keyword `k`
  by dropping any preceding '<table>.' portion. If `k` is a
  two-element keyword sequence (following format of honeysql
  select), returns the second keyword. Returns nil if `k` does not
  seem to represent a column; presence of '%' indicates that `k`
  represents an sql function call rather than a column."
  [k]
  (if (and (sequential? k) (= (count k) 2) (every? keyword? k))
    (when-test keyword? (second k))
    (some-> (when-test keyword? k)
            name
            ((when-test (fn [s] (not (str/includes? s "%")))))
            (str/split #"\.")
            last
            keyword)))

(def ^:private max-sql-query-literal-values 1000)

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
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- Query DSL - helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Query DSL - top-level functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: disallow LIMIT and ORDER BY when splitting with
;;       `split-match-by-value-seqs`?
;; TODO: splitting queries like this also breaks `find-count`
(defn-spec find any?
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

  `join` and `left-join` can take the following forms:
    -  [[:table2 :t2] [:= :t2.join.field :t1.join-field]]
    -  [[:table2 :t2] :t1.join-field1]
  Multiple forms can be put together in a sequence:
    -  [[[:table2 :t2] :t1.join-field1]
        [[:table3 :t3] :t2.join-field2]]

  `return` optionally modifies the behavior and return value.
  - `:execute` (default behavior) runs the query against the connected
    database and returns the processed result.
  - `:query` returns the honeysql query map that would be run against
    the database.
  - `:string` returns an SQL string corresponding to the query."
  ([table ::table, match-by ::match-by]
   (find table match-by :*))
  ([table ::table, match-by ::match-by, fields ::fields
    & {:keys [index-by group-by join left-join where limit order-by group prepare return]
       :or {return :execute}
       :as opts} (opt-keys ::index-by ::group-by ::join ::left-join ::where
                           ::limit ::order-by ::group ::prepare ::return)]
   (assert (every? #{:index-by :group-by :join :left-join :where
                     :limit :order-by :group :prepare :return}
                   (keys opts)))
   (let [return (or return :execute)
         single-field (or (some->> fields (when-test literal?) extract-column-name)
                          (some->> fields
                                   (when-test #(and (coll? %)
                                                    (= 1 (count %))
                                                    (coll? (first %))
                                                    (= 2 (count (first %)))
                                                    (every? keyword? (first %))))
                                   first
                                   extract-column-name
                                   (when-test literal?)))
         specified (some->> (cond (keyword? fields)  [fields]
                                  (empty? fields)    nil
                                  :else              fields)
                            (map extract-column-name)
                            (when-test (partial every? literal?)))
         fields (cond (keyword? fields)  [fields]
                      (empty? fields)    [:*]
                      :else              (vec fields))
         select-fields (as-> (concat fields
                                     (some->> index-by (when-test keyword?) (list))
                                     (some->> group-by (when-test keyword?) (list)))
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
                          (cond-> order-by (#(apply sqlh/order-by %
                                                    (if (or ((comp not coll?) order-by)
                                                            (and (sequential? order-by)
                                                                 (every? (comp not coll?) order-by)))
                                                      [order-by] order-by))))
                          (cond-> group (#(apply sqlh/group % (collify group))))
                          (cond-> prepare (prepare))))]
     (util/assert-exclusive index-by group-by)
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
       :string   (db/to-sql-string (make-query match-by))))))

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
#_ (find [:project :p] {:p.project-id 100} :pm.user-id
         :join [[:project-member :pm] :p.project-id]
         :order-by :user-id, :limit 5)
;;; => (9 26 33 35 37)
#_ (find [:project :p] {:p.project-id 100} :u.user-id
         :join [[[:project-member :pm] [:= :p.project-id :pm.project-id]]
                [[:web-user :u] :pm.user-id]]
         :order-by :user-id, :limit 5)
;;; => (9 26 33 35 37)

(defn-spec find-one any?
  "Runs `find` and returns a single result entry (or nil if none found),
  throwing an exception if the query returns more than one result."
  ([table ::table, match-by ::match-by]
   (find-one table match-by :*))
  ([table ::table, match-by ::match-by, fields ::fields
    & {:keys [where prepare join left-join return]
       :or {return :execute}
       :as opts} (opt-keys ::where ::prepare ::join ::left-join ::return)]
   (assert (every? #{:where :prepare :join :left-join :return} (keys opts)))
   (let [execute? (= return :execute)
         result (apply-keyargs find table match-by fields
                               (assoc opts :prepare #(cond-> %
                                                       prepare   (prepare)
                                                       execute?  (sqlh/limit 2))))]
     (if execute?
       (first (->> result (assert-pred {:pred #(<= (count %) 1)
                                        :message "find-one - multiple results from query"})))
       result))))

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

(defn-spec find-count (s/or :count int? :query map?)
  "Convenience function to return count of rows matching query.

  See `find` for description of all arguments."
  [table ::table, match-by ::match-by
   & {:keys [where prepare join left-join return]
      :as opts} (opt-keys ::where ::prepare ::join ::left-join ::return)]
  (apply-keyargs find-one table match-by [[:%count.* :count]] opts))

(s/def ::opts-exists (opt-keys ::join ::left-join ::where ::prepare))

(defn-spec exists vector?
  "Runs (find ... :return :query) and wraps result in [:exists ...].

  See `find` for description of all arguments."
  [table ::table, match-by ::match-by
   & {:keys [join left-join where prepare] :as opts} ::opts-exists]
  (assert (every? #{:join :left-join :where :prepare} (keys opts)))
  [:exists (apply-keyargs find table match-by :*
                          (assoc opts :return :query))])

(defn-spec not-exists vector?
  "Runs (find ... :return :query) and wraps result in [:not [:exists ...].

  See `find` for description of all arguments."
  [table ::table, match-by ::match-by
   & {:keys [join left-join where prepare]:as opts} ::opts-exists]
  [:not (apply-keyargs exists table match-by opts)])

(defn-spec create any?
  "Runs insert query on `table` using sequence of maps `insert-values`.

  Returns a count of rows updated.

  `insert-values` may also be a map and will be treated as a single
  entry; if a `returning` argument is given, this will give a return
  value of one entry rather than a sequence of entries.

  `returning` optionally takes a sequence of field keywords to use as
  arguments to a Postgres-specific returning clause. `returning` may
  also be a single keyword; in this case the return value will be a
  sequence of values for this field rather than a sequence of field
  value maps. Wildcard keywords (e.g. `:*`, `:table.*`) are also
  allowed.

  See `find` for description of all arguments."
  [table ::table, insert-values ::insert-values
   & {:keys [returning prepare return]
      :or {return :execute}
      :as opts} (opt-keys ::returning ::prepare ::return)]
  (assert (every? #{:returning :prepare :return} (keys opts)))
  (let [single-value? (map? insert-values)
        insert-values (ensure-vector insert-values)
        single-returning? (literal? returning)
        returning (ensure-vector returning)]
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

(defn-spec modify any?
  "Runs update query on `table` filtered according to `match-by`,
  setting field values according to `set-values`.

  Returns a count of rows updated.

  `set-values` should match format of honeysql (sset ...) function.

  See `find` and `create` for description of all arguments."
  [table ::table, match-by ::match-by, set-values ::set-values
   & {:keys [where returning prepare join left-join return]
      :or {return :execute}
      :as opts} (opt-keys ::where ::returning ::prepare ::join ::left-join ::return)]
  (assert (every? #{:where :returning :prepare :join :left-join :return} (keys opts)))
  (let [single-returning? (and (keyword? returning) (not= returning :*))
        returning (ensure-vector returning)]
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

(defn-spec delete any?
  "Runs delete query on `table` filtered according to `match-by`.

  Returns a count of rows deleted.

  See `find` for description of all arguments."
  [table ::table, match-by ::match-by
   & {:keys [where prepare join left-join return]
      :or {return :execute}
      :as opts} (opt-keys ::where ::prepare ::join ::left-join ::return)]
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
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- Query DSL - top-level functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ def-find-type macro (find-*, find-*-1, get-*)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmacro --def-find-type-impl
  "Defines table-specific functions query functions.
  find-<table> find-<table>-1 get-<table>"
  [find-fn
   [table alias]
   {:keys [id-field table-name join-default join-specs match-by opts]
    :or {table-name nil join-default [] join-specs {}}
    :as _customize}
   {:keys [custom-opts -match-by -fields -opts -with]
    :or {custom-opts []}
    :as _symbols}]
  (assert (->> (some-> find-fn name symbol)
               (in? #{'find 'find-one nil}))
          (format "invalid value for find-fn: %s" (some-> find-fn (symbol))))
  (assert (keyword? table))
  (assert (keyword? alias))
  (assert (->> id-field (s/valid? (s/nilable symbol?))))
  (assert (->> table-name (s/valid? (s/nilable string?))))
  (assert (symbol? -match-by))
  (assert (symbol? -fields))
  (assert (symbol? -opts))
  (assert (symbol? -with))
  (assert (->> custom-opts (s/valid? (s/coll-of symbol? :kind vector?))))
  (assert (->> join-specs (s/valid? (s/map-of keyword? ::join-single))))
  (assert (->> join-default (s/valid? (s/coll-of keyword? :kind vector?))))
  (let [table-name   (or table-name (name table))
        find-name    (symbol (str "find-" table-name))
        find-name-1  (symbol (str "find-" table-name "-1"))
        table-dot-*  (keyword (format "%s.*" (name alias)))
        find-name-fn (condp = (some-> find-fn name symbol)
                       'find      find-name
                       'find-one  find-name-1
                       nil)]
    (if (symbol? find-fn)
      `(defn ~find-name-fn
         ([~-match-by & [~-fields & {:keys [~-with ~@custom-opts]
                                     :or {~-with ~join-default}
                                     :as ~-opts}]]
          (apply-keyargs
           ~find-fn ~[table alias]
           ~(or match-by -match-by)
           (or ~-fields ~table-dot-*)
           (let [default-join-arg# (map #(get ~join-specs %) ~-with)]
             (as-> ~-opts ~-opts
               (update ~-opts :join
                       #(some-> (concat default-join-arg# %) seq vec))
               (cond-> ~-opts (nil? (:join ~-opts)) (dissoc :join))
               (apply dissoc ~-opts
                      ~(keyword -with) [~@(map keyword custom-opts)])
               ~(or opts -opts)))))
         {:doc ~(format "`%s` for table %s with specialized default options.

  `%s` is a list of keywords naming tables to join with; values allowed are %s."
                        (name find-fn) table
                        (name -with) (-> join-specs keys vec pr-str))})
      (when (symbol? id-field)
        `(defn ~(symbol (str "get-" table-name))
           ([~id-field & [~-fields & {:keys [~-with ~@custom-opts] :as ~-opts}]]
            (apply-keyargs (if (sequential? ~id-field) ~find-name ~find-name-1)
                           {~(keyword (format "%s.%s" (name alias) (name id-field)))
                            ~id-field}
                           (or ~-fields ~table-dot-*)
                           ~-opts))
           {:doc ~(format "Query table %s by value of %s.

  If `%s` is a sequence, calls `%s`; otherwise `%s`.

  `%s` is a list of keywords naming tables to join with; values allowed are %s."
                          table (keyword id-field)
                          (name id-field) (name find-name) (name find-name-1)
                          (name -with) (-> join-specs keys vec pr-str))})))))

(defmacro def-find-type
  "Defines table-specific functions query functions.
  find-<table> find-<table>-1 get-<table>"
  [[table alias]
   {:keys [id-field table-name join-default join-specs match-by opts]
    :or {table-name nil join-default [] join-specs {}}
    :as customize}
   {:keys [custom-opts -match-by -fields -opts -with]
    :or {custom-opts []}
    :as symbols}]
  `(list (--def-find-type-impl find      [~table ~alias] ~customize ~symbols)
         (--def-find-type-impl find-one  [~table ~alias] ~customize ~symbols)
         (--def-find-type-impl nil       [~table ~alias] ~customize ~symbols)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- def-find-type macro (find-*, find-*-1, get-*)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ def-find-type table definitions (find-*, find-*-1, get-*)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare find-project find-project-1 get-project)
(def-find-type [:project :p]
  {:id-field       project-id
   :join-default   []
   :join-specs     {:label           [[:label :l]            :p.project-id]
                    :project-member  [[:project-member :pm]  :p.project-id]
                    :web-user        [[:web-user :u]         :pm.user-id]
                    :project-note    [[:project-note :pn]    :p.project-id]}
   :match-by       (cond->> match-by
                     (not include-disabled) (merge {:p.enabled true}))}
  {:custom-opts [include-disabled]
   :-match-by match-by :-fields fields :-opts opts :-with with})

(declare find-user find-user-1 get-user)
(def-find-type [:web-user :u]
  {:id-field       user-id
   :table-name     "user"
   :join-default   []
   :join-specs     {:project-member  [[:project-member :pm]  :u.user-id]
                    :project         [[:project :p]          :pm.project-id]}}
  {:-match-by match-by :-fields fields :-opts opts :-with with})

(declare find-label find-label-1 get-label)
(def-find-type [:label :l]
  {:id-field       label-id
   :join-default   []
   :join-specs     {:project         [[:project :p]          :l.project-id]
                    :article-label   [[:article-label :al]   :l.label-id]}
   :match-by       (cond->> match-by
                     (not include-disabled) (merge {:l.enabled true}))}
  {:custom-opts [include-disabled]
   :-match-by match-by :-fields fields :-opts opts :-with with})

(declare find-article find-article-1 get-article)
(def-find-type [:article :a]
  {:id-field      article-id
   :join-default  [:article-data]
   :join-specs    {:project         [[:project :p]          :a.project-id]
                   :article-data    [[:article-data :ad]    :a.article-data-id]
                   :article-label   [[:article-label :al]   :a.article-id]
                   :label           [[:label :l]            :al.label-id]
                   :web-user        [[:web-user :u]         :al.user-id]
                   :article-note    [[:article-note :an]    :a.article-id]
                   :project-note    [[:project-note :pn]    :an.project-note-id]
                   :article-resolve [[:article-resolve :ar] :ar.article-id]
                   :predict-run     [[:predict-run :pr]     :p.project-id]}
   :match-by      (cond->> match-by
                    (and (not include-disabled)
                         (not include-disabled-source)) (merge {:a.enabled true}))
   :opts          (cond-> opts
                    include-disabled-source
                    (update :where #(vector :and (if (some? %) % true)
                                            (not-exists [:article-flag :af-1]
                                                        {:af-1.article-id :a.article-id
                                                         :af-1.disable true}))))}
  {:custom-opts [include-disabled include-disabled-source]
   :-match-by match-by :-fields fields :-opts opts :-with with})

(defn label-confirmed-test [confirmed?]
  (case confirmed?
    true [:!= :confirm-time nil]
    false [:= :confirm-time nil]
    true))
(defn where-valid-article-label [confirmed?]
  [:and (label-confirmed-test confirmed?)
   [:!= :al.answer nil]
   [:!= :al.answer (db/to-jsonb nil)]
   [:!= :al.answer (db/to-jsonb [])]])
(defn filter-valid-article-label [m confirmed?]
  (merge-where m (where-valid-article-label confirmed?)))

(declare find-article-label find-article-label-1)
(def-find-type [:article-label :al]
  {:join-default  []
   :join-specs    {:article       [[:article :a]        :al.article-id]
                   :label         [[:label :l]          :al.label-id]
                   :web-user      [[:web-user :u]       :al.user-id]
                   :project       [[:project :p]        :a.project-id]
                   :article-data  [[:article-data :ad]  :a.article-data-id]}
   :match-by      (cond->> match-by
                    (not include-disabled)
                    (merge {:a.enabled true, :l.enabled true}))
   :opts          (update opts :where
                          #(apply vector :and (if (some? %) % true)
                                  (->> [[filter-valid        (where-valid-article-label nil)]
                                        [(true? confirmed)   [:!= :al.confirm-time nil]]
                                        [(false? confirmed)  [:= :al.confirm-time nil]]]
                                       (filter first) (map second))))}
  {:custom-opts [include-disabled filter-valid confirmed]
   :-match-by match-by :-fields fields :-opts opts :-with with})
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- def-find-type table definitions (find-*, find-*-1, get-*)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Article queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- Article queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Label queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn select-label-where
  [project-id where-clause fields & [{:keys [include-disabled?]
                                      :or {include-disabled? false} :as _opts}]]
  (cond-> (-> (apply select fields)
              (from [:label :l]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)

    project-id (merge-where [:= :project-id project-id])

    (not include-disabled?) (merge-where [:= :enabled true])))

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

(defn filter-label-user [m user-id]
  (-> m (merge-where [:= :al.user-id user-id])))

(defn select-project-article-labels [project-id confirmed? fields]
  (-> (select-project-articles project-id fields)
      (join-article-labels)
      (join-article-label-defs)
      (filter-valid-article-label confirmed?)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- Label queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Project queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- select-project-where [where-clause fields]
  (cond-> (-> (apply select fields) (from [:project :p]))
    (and (not (nil? where-clause))
         (not (true? where-clause))) (merge-where where-clause)))

(defn query-project-by-id [project-id fields]
  (-> (select-project-where [:= :p.project-id project-id] fields)
      do-query first))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ User queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn select-project-members [project-id fields]
  (-> (apply select fields)
      (from [:project-member :m])
      (join [:web-user :u]
            [:= :u.user-id :m.user-id])
      (where [:= :m.project-id project-id])))

(defn join-users [m user-id]
  (merge-join m [:web-user :u] [:= :u.user-id user-id]))

(defn-spec filter-user-permission map?
  [m map?, permission string?, & [not?] (s/cat :not? (s/? boolean?))]
  (let [test (db/sql-array-contains :u.permissions permission)
        test (if not? [:not test] test)]
    (merge-where m test)))

(defn-spec filter-admin-user map?
  [m map?, admin? (s/nilable boolean?)]
  (cond-> m
    (true? admin?)  (filter-user-permission "admin")
    (false? admin?) (filter-user-permission "admin" true)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- User queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Label prediction queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn join-article-predict-values [m & [predict-run-id]]
  (if-not predict-run-id m
          (-> m
              (merge-left-join [:label-predicts :lp]
                               [:= :lp.article-id :a.article-id])
              (merge-where [:or
                            [:= :lp.predict-run-id nil] ; no left-join match
                            [:= :lp.predict-run-id predict-run-id]])
              (merge-where [:or
                            [:= :lp.stage nil] ; no left-join match
                            [:= :lp.stage 1]]))))

(defn project-latest-predict-run-id
  "Gets the most recent predict-run ID for a project."
  [project-id]
  (db/with-project-cache project-id [:predict :latest-predict-run-id]
    (first (find :predict-run {:project-id project-id} :predict-run-id
                 :order-by [:create-time :desc] :limit 1))))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; -- Label prediction queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ++ Utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn table-exists? [table]
  (try (find table {} :*, :limit 1) true
       (catch Throwable _ false)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
