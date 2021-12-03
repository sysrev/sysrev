(ns sysrev.source.pubmed
  (:require [sysrev.config :as config]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.source.core :as source :refer [make-source-meta re-import]]
            [sysrev.source.interface :refer [import-source import-source-impl import-source-articles after-source-import]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db :refer [do-query]]
            [honeysql.helpers :as sqlh :refer [select from where join]]
            [sysrev.util :as util :refer [parse-integer]]))

(defn pubmed-get-articles [pmids]
  (->> (map parse-integer pmids)
       sort
       (partition-all 500)
       (map (fn [pmids]
              (->> (vals (ds-api/fetch-pubmed-articles pmids :fields [:primary-title]))
                   (map (fn [{:keys [primary-title pmid]}]
                          (when (and pmid (not-empty primary-title))
                            {:external-id (str pmid) :primary-title primary-title})))
                   (remove nil?)
                   vec)))
       (apply concat)
       vec))

(defn- pubmed-source-exists? [project-id search-term]
  (->> (source/project-sources project-id)
       (filter #(= (get-in % [:meta :search-term]) search-term))
       not-empty))

(defmethod make-source-meta :pubmed
  [_ {:keys [search-term search-count]}]
  {:source "PubMed search"
   :search-term search-term
   :search-count search-count})

(defmethod import-source :pubmed
  [request _ project-id {:keys [search-term]} {:as options}]
  (assert (string? search-term))
  (let [{:keys [max-import-articles]} config/env
        pmids-count (:count (pubmed/get-search-query-response search-term 1))]
    (cond
      (pubmed-source-exists? project-id search-term)
      {:error {:message (format "Source already exists for %s" (pr-str search-term))}}

      (> pmids-count max-import-articles)
      {:error {:message (format "Too many PMIDs from search (max %d; got %d)"
                                max-import-articles pmids-count)}}

      :else
      (let [source-meta (source/make-source-meta
                         :pubmed {:search-term search-term
                                  :search-count pmids-count})]
        (import-source-impl
         request project-id source-meta
         {:types {:article-type "academic" :article-subtype "pubmed"}
          :get-article-refs #(pubmed/get-all-pmids-for-query search-term)
          :get-articles pubmed-get-articles}
         options)))))

(defn get-new-articles-available [{:keys [source-id] :as source}]
  (let [prev-article-ids (->> (-> (select :article-data.external-id)
                                  (from [:article-source :asrc])
                                  (join :article [:= :asrc.article-id :article.article-id]
                                        :article-data [:= :article.article-data-id :article-data.article-data-id])
                                  (where [:= :asrc.source-id source-id])
                                  do-query)
                              (map :external-id)
                              (filter string?)
                              (map read-string)
                              set)
        new-article-ids (->> (pubmed/get-all-pmids-for-query (get-in source [:meta :search-term]))
                             (filter #(not (contains? prev-article-ids %))))]
    new-article-ids))


(defmethod re-import "PubMed search" [request project-id {:keys [source-id] :as source}]
  (let [threads 4
        do-import (fn []
                    (->> (import-source-articles
                           request project-id source-id
                           {:types {:article-type "academic" :article-subtype "pubmed"}
                            :article-refs (get-new-articles-available source)
                            :get-articles pubmed-get-articles}
                           threads)
                         (after-source-import request project-id source-id)))]
    (source/alter-source-meta source-id #(assoc % :importing-articles? true))
    (source/set-import-date source-id)
    (future (do-import))
    {:source-id source-id}))

