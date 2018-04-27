(ns sysrev.cache
  (:require [clojure.core.cache :as cache :refer [defcache
                                                  CacheProtocol
                                                  basic-cache-factory]]
            [clojure.core.memoize :refer [build-memoizer]]
            [clojure.java.jdbc :refer [query insert!]])
  (:import [clojure.core.memoize PluggableMemoization]
           [clojure.core.cache BasicCache]))

(defn id
  [n]
  (do (Thread/sleep 5000) (identity n)))

;; from https://www.postgresql.org/docs/9.1/static/datatype-character.html
;; tip: there is no performance difference among these three types, apart from increased storage space when using the blank-padded type, and a few extra cpu cycles to check the length when storing into a length-constrained column. while character(n) has performance advantages in some other database systems, there is no such advantage in postgresql; in fact character(n) is usually the slowest of the three because of its additional storage costs. in most situations text or character varying should be used instead.
;;
;; therefore because we need an unlimited string size, columns of type text are used

(def cache-table :sysrev_cache)

;; This was based on code from
;; https://github.com/boxuk/groxy/blob/master/src/clojure/groxy/cache/db.clj
;; but has been modified to work with clojure.core.memoize

(defn find-row [db id]
  (let [sql (format "select id, data from %s where id = ?"
                    (name cache-table))]
    (query db [sql (pr-str id)])))

(defn lookup
  ([db id] (lookup db id nil))
  ([db id not-found]
   (let [res (find-row db id)]
     (if-let [row (first res)]
       (read-string (:data row))
       not-found))))

(defn store [db id data]
  (insert! db cache-table
           {:id (pr-str id)
            :data (pr-str @data)}))

(defn purge [db id])

(defcache DatabaseCache [cache db]
  CacheProtocol
  (lookup [_ item]
          (delay (lookup db item)))
  (lookup [_ item not-found]
          (delay (lookup db item)))
  (has? [_ item]
        (not (nil? (lookup db item))))
  (hit [this item]
       this)
  (miss [this item ret]
        (store db item ret)
        this)
  (evict [_ item]
         (purge db item))
  (seed [_ base]
        (DatabaseCache. base db))
  Object
  (toString [_] (str cache)))

(defn database-cache-factory
  "A durable JDBC database backed cache."
  [base db]
  (DatabaseCache. base db))

;; end code from https://github.com/boxuk/groxy/blob/master/src/clojure/groxy/cache/db.clj

;; make-derefable and derefable-seed were private functions in clojure.core.memoize, brought in here

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

(defn dbmemo
  "Based upon memo from clojure.core.memoize"
  ([f] (dbmemo f {}))
  ([f seed]
   (clojure.core.memoize/build-memoizer
    #(PluggableMemoization. %1 (database-cache-factory %2 @sysrev.db.core/active-db))
    f
    (derefable-seed seed))))

(def db-id
  (dbmemo id))

;; TODO:

;; clean up ns :require and :import declarations
;; dbmemo should specify both db and table name
;; check to see if memoize snapshot / lazy-snapshot / memoized? / memo-clear! / memo-swap! functions from clojure.core.memoize work

;; look up how indexing works in postgresql when you do it by text
;; is it already hashing the text internally to create indices?

