(ns sysrev.misc
  (:require [clojure.string :as str]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [delete-from where insert-into values]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.util :as util :refer [map-values]]))

(defn articles-matching-regex-clause [field-name regexs]
  (sql/raw
   (str "( "
        (->>
         (mapv (fn [regex]
                 (format " (%s ~ '%s') "
                         (first (sql/format field-name))
                         regex))
               regexs)
         (str/join " OR "))
        " )")))

(defn fix-duplicate-label-values [label-id]
  (let [label (->> (q/select-label-by-id label-id [:*])
                   do-query first)
        {:keys [all-values inclusion-values]} (:definition label)]
    (cond-> label
      all-values        (update-in [:definition :all-values]
                                   #(->> % (remove empty?) distinct))
      inclusion-values  (update-in [:definition :inclusion-values]
                                   #(->> % (remove empty?) distinct)))))

(defn merge-article-labels [article-ids]
  (let [labels
        (->> article-ids
             (mapv (fn [article-id]
                     (-> (q/select-article-by-id article-id [:al.*])
                         (q/join-article-labels)
                         do-query)))
             (apply concat)
             (group-by :user-id)
             (map-values #(group-by :article-id %))
             (map-values vec)
             (map-values #(sort-by (comp count second) > %)))]
    (doseq [[user-id ulabels] labels]
      (let [confirmed-labels
            (->> ulabels (filter (fn [[_article-id alabels]]
                                   (some #(not= (:confirm-time %) nil)
                                         alabels))))
            keep-labels (if (seq confirmed-labels)
                          (second (first confirmed-labels))
                          (second (first ulabels)))]
        (when (seq keep-labels)
          (println (format "keeping %d labels for user=%s"
                           (count keep-labels) user-id))
          (with-transaction
            (doseq [article-id article-ids]
              (-> (delete-from :article-label)
                  (where [:and
                          [:= :article-id article-id]
                          [:= :user-id user-id]])
                  do-execute)
              (-> (insert-into :article-label)
                  (values
                   (->> keep-labels
                        (map
                         #(-> %
                              (assoc :article-id article-id)
                              (update :answer to-jsonb)
                              (dissoc :article-label-id)
                              (dissoc :article-label-local-id)))))
                  do-execute))))))
    true))
