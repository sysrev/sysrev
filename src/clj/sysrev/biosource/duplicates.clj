(ns sysrev.biosource.duplicates
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [select from where insert-into
                                               values delete-from]]
            [sysrev.db.core :as db :refer [do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.source.core :as source]
            [sysrev.util :as util :refer [in?]]))

(def flag-name "auto-duplicate")

(defn get-project-duplicates [project-id]
  (let [request (->>
                 (q/find-article {:project-id project-id}
                                 [:a.article-id :ad.primary-title :ad.abstract])
                 (mapv (fn [{:keys [article-id primary-title abstract]}]
                         {:id article-id
                          :text (str/join "\n" [(or primary-title "")
                                                (or abstract "")])})))
        response (http/post (str api-host "sysrev/deduplication")
                            {:content-type "application/json"
                             :body (util/write-json request)})]
    (util/read-json (:body response))))

(defn merge-duplicate-pairs [duplicates]
  (let [all-pairs (->> duplicates (map (fn [{:keys [idA idB]}] [idA idB])))
        all-article-ids (->> all-pairs (apply concat) distinct)
        lookup-article-dups
        (fn [article-id]
          (->> all-pairs
               (filter #(in? % article-id))
               (apply concat)
               distinct
               (sort <)))
        article-dups (map lookup-article-dups all-article-ids)
        lookup-group-matches
        (fn [article-group-1]
          (->> article-dups
               (filter (fn [article-group-2]
                         (some (fn [article-id]
                                 (in? article-group-1 article-id))
                               article-group-2)))
               (apply concat)
               distinct
               (sort <)))]
    (->> article-dups (map lookup-group-matches) distinct)))

(defn ^:unused update-project-duplicates [project-id]
  (db/with-clear-project-cache project-id
    (-> (delete-from [:article-flag :af])
        (where [:and
                [:= :af.flag-name flag-name]
                [:exists
                 (-> (select :*)
                     (from [:article :a])
                     (where [:and
                             [:= :a.article-id :af.article-id]
                             [:= :a.project-id project-id]]))]])
        do-execute)
    (source/update-project-articles-enabled project-id)
    (let [entries
          (->> (get-project-duplicates project-id)
               (merge-duplicate-pairs)
               (map
                (fn [article-ids]
                  (->>
                   article-ids
                   (map (fn [article-id]
                          {:article-id article-id
                           :flag-name flag-name
                           ;; keep first id enabled
                           :disable (not= article-id (first article-ids))
                           :meta (db/to-jsonb {:duplicate-of article-ids})})))))
               (apply concat))]
      (log/info (format "marking %d article duplicates..." (count entries)))
      (doseq [entry-group (partition-all 100 entries)]
        (-> (insert-into :article-flag)
            (values (vec entry-group))
            do-execute)))
    (source/update-project-articles-enabled project-id)
    nil))
