(ns sysrev.db.query-types
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :as sqlh-pg :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as sutil :refer [apply-keyargs]]))

(defmacro def-find-type [[table alias] [match-by fields opt-keys opts] & find-args]
  (let [find-name (symbol (str "find-" (name table)))
        find-name-1 (symbol (str "find-" (name table) "-1"))]
    `(list (defn ~find-name [~match-by ~fields & {:keys [~@opt-keys] :as ~opts}]
             (apply-keyargs q/find [~table ~alias] ~@find-args))
           (defn ~find-name-1 [~match-by ~fields & {:keys [~@opt-keys] :as ~opts}]
             (apply-keyargs q/find-one [~table ~alias] ~@find-args)))))

(def-find-type [:label :l]
  [match-by fields [include-disabled] opts]
  (cond-> match-by
    (not include-disabled) (merge {:l.enabled true}))
  fields
  (dissoc opts :include-disabled))

(defn get-label [label-id & [fields & {:as opts}]]
  (apply-keyargs find-label-1 {:l.label-id label-id} (or fields :*)
                 (merge opts {:include-disabled true})))

(def-find-type [:article :a]
  [match-by fields [include-disabled include-disabled-source] opts]
  (cond-> match-by
    (and (not include-disabled)
         (not include-disabled-source)) (merge {:a.enabled true}))
  fields
  (-> (dissoc opts :include-disabled :include-disabled-source)
      (cond-> include-disabled-source
        (assoc :prepare #(-> (merge-where
                              % (q/not-exists [:article-flag :af-1]
                                              {:af-1.article-id :a.article-id
                                               :af-1.disable true}))
                             (cond-> (:prepare opts) ((:prepare opts))))))))

(defn get-article [article-id & [fields & {:as opts}]]
  (apply-keyargs find-article-1 {:a.article-id article-id} (or fields :*)
                 (merge opts {:include-disabled true})))
