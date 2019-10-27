(ns sysrev.cache
  (:require [clojure.string :as str]
            [clojure.core.cache :as cache :refer [defcache CacheProtocol]]
            clojure.core.memoize
            [clojure.main :refer [demunge]]
            [sysrev.db.queries :as q])
  (:import [clojure.core.memoize PluggableMemoization]))

;; from https://www.postgresql.org/docs/9.1/static/datatype-character.html
;; tip: there is no performance difference among (character types)
;;
;; minimum table structure (PostgreSQL):
;;
;; CREATE TABLE memo_cache (f text, params text, result text);
;;
;; text is the col type used in postgresql for unlimited length strings
;;
;;  Column | Type | Modifiers
;; --------+------+-----------
;;  f      | text |
;;  params | text |
;;  result | text |
;;
;; recommended table structure (PostgreSQL):
;;
;; CREATE TABLE memo_cache (f text, params text, result text, created timestamp with time zone not null default now());
;;
;; This is so that you can clear out the cache after a certain time
;; interval

(defn- fn->string
  "Given a fn, return a string representation of that function"
  [f]
  (-> (type f) str demunge (str/replace #"class " "")))

;; see also:
;; https://github.com/boxuk/groxy/blob/master/src/clojure/groxy/cache/db.clj

(defn- find-results
  "Given a database definition,db, a string representing a function
  name f (i.e. string returned by fn->string) and params, look up any
  result associated with it in the db"
  [_db f params]
  ;; note: this is sysrev specific
  (q/find :memo-cache {:f (pr-str f), :params (pr-str params)}
          [:params :result]))

(defn- lookup
  "Lookup results when calling f by params"
  ([db f params] (lookup db f params nil))
  ([db f params not-found]
   (let [res (find-results db f params)]
     (if-let [row (first res)]
       (read-string (:result row))
       not-found))))

(defn- store [_db f params result]
  ;; this is sysrev specific
  (q/create :memo-cache {:params (pr-str params)
                         :result (pr-str @result)
                         :f (pr-str f)}))

(defn- purge [_db _params])

;; the dereferencing of db as an atom is particular to the case
;; where pooled connections are stored in an atom
(defcache SQLMemoCache [cache db f]
  CacheProtocol
  (lookup [_ item]
          (delay (lookup @db f item)))
  (lookup [_ item not-found]
          (delay (lookup @db f item)))
  (has? [_ item]
        (not (nil? (lookup @db f item))))
  (hit [this item]
       this)
  (miss [this item ret]
        (store @db f item ret)
        this)
  (evict [_ item]
         (purge @db item))
  (seed [_ base]
        (SQLMemoCache. base @db f))
  Object
  (toString [_] (str cache)))

(defn- sql-memo-cache-factory
  "Return a durable SQL DB backed-cache for memoization of function f
  given a db definition and base cache"
  [base db f]
  (SQLMemoCache. base db f))

;; make-derefable and derefable-seed were private functions in
;; clojure.core.memoize, brought in here

(defn- make-derefable
  "If a value is not already derefable, wrap it up.

  This is used to help rebuild seed/base maps passed in to the various
  caches so that they conform to core.memoize's world view."
  [v]
  (if (instance? clojure.lang.IDeref v)
    v
    (reify clojure.lang.IDeref
      (deref [_] v))))

(defn- derefable-seed
  "Given a seed/base map, ensure all the values in it are derefable."
  [seed]
  (into {} (for [[k v] seed] [k (make-derefable v)])))

(defn db-memo
  "Based upon memo from clojure.core.memoize, except it is backed by a SQL
  database. The table that stores the fn params does so per function
  name. Define your functions with defn before db-memo'izing them.
  Note that defining a db-memo'ized fn for an anonymous function e.g.
  (db-memo (fn [x] (identity x)))
  will result in a cache table with multiple caches for the same
  function. It is recommended to use traditional memo for that use
  case"
  ([db f] (db-memo db f {}))
  ([db f seed]
   (clojure.core.memoize/build-memoizer
    #(PluggableMemoization. %1 (sql-memo-cache-factory %2 db (fn->string f)))
    f
    (derefable-seed seed))))

;; note: for functions memo'ized with db-memo, the
;; clojure.core.memoize fn's snapshot and lazy-snapshot return and
;; empty map memoized? correctly returns true memo-clear! and
;; memo-swap! can be called, but have no effect on the actual results
;; This is due to the fact that we are always calling from the DB and
;; not the derefable associated with the cache
