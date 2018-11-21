(ns sysrev.biosource.duplicates
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-jsonb
              clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.source.core :as source]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values parse-number in?]]
            [sysrev.config.core :as config]
            [sysrev.biosource.core :refer [api-host]]))

(def flag-name "auto-duplicate")

(defn get-project-duplicates [project-id]
  (let [request
        (->> (q/select-project-articles
              project-id [:a.article-id :a.primary-title :a.abstract])
             do-query
             (mapv (fn [{:keys [article-id primary-title abstract]}]
                     {:id article-id
                      :text (str/join "\n" [(or primary-title "")
                                            (or abstract "")])})))
        response
        (-> (http/post (str api-host "sysrev/deduplication")
                       {:content-type "application/json"
                        :body (json/write-str request)})
            :body
            (json/read-str :key-fn keyword))]
    response))

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

(defn update-project-duplicates [project-id]
  (with-transaction
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
                           :meta (to-jsonb
                                  {:duplicate-of article-ids})})))))
               (apply concat))]
      (log/info (format "marking %d article duplicates..." (count entries)))
      (doseq [entry-group (partition-all 100 entries)]
        (-> (insert-into :article-flag)
            (values (vec entry-group))
            do-execute)))
    (source/update-project-articles-enabled project-id)
    (clear-project-cache project-id)
    nil))
