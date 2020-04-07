(ns sysrev.db.query-types
  (:require [honeysql.helpers :as sqlh :refer [merge-where]]
            [sysrev.db.queries :as q]
            [sysrev.util :as util :refer [apply-keyargs]]))

;; for clj-kondo
(declare find-label find-label-1 find-article find-article-1)

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
      (update :join #(vec (conj (seq %) [:article-data:ad :a.article-data-id])))
      (cond-> include-disabled-source
        (update :prepare (fn [prepare]
                           #((or prepare identity)
                             (merge-where % (q/not-exists [:article-flag :af-1]
                                                          {:af-1.article-id :a.article-id
                                                           :af-1.disable true}))))))))

(defn get-article [article-id & [fields & {:as opts}]]
  (apply-keyargs find-article-1 {:a.article-id article-id} (or fields :*)
                 (merge opts {:include-disabled true})))
