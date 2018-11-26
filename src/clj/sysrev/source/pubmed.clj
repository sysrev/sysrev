(ns sysrev.source.pubmed
  (:require [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.config.core :as config]
            [sysrev.pubmed :as pubmed]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import :refer [import-source]]
            [sysrev.shared.util :as su :refer [in?]]))

(defn pubmed-get-articles [pmids]
  (->> pmids sort
       (partition-all (if pubmed/use-cassandra-pubmed? 300 40))
       (map #(if pubmed/use-cassandra-pubmed?
               (pubmed/fetch-pmid-entries-cassandra %)
               (pubmed/fetch-pmid-entries %)))
       (apply concat)
       (filter #(and %
                     (:public-id %)
                     (not-empty (:primary-title %))))))

(defn- pubmed-source-exists? [project-id search-term]
  (->> (source/project-sources project-id)
       (filter #(= (get-in % [:meta :search-term]) search-term))
       not-empty))

(defmethod import-source :pubmed
  [project-id stype {:keys [search-term]}
   {:keys [use-future? threads] :or {use-future? true threads 1}}]
  (let [_ (assert (string? search-term))
        {:keys [max-import-articles]} config/env
        pmids-count (:count (pubmed/get-search-query-response search-term 1))]
    (cond
      (pubmed-source-exists? project-id search-term)
      {:error (format "Source already exists for %s" (pr-str search-term))}

      (> pmids-count max-import-articles)
      {:error (format "Too many PMIDs from search (max %d; got %d)"
                      max-import-articles pmids-count)}

      :else
      (let [pmids (pubmed/get-all-pmids-for-query search-term)
            source-meta (source/make-source-meta
                         :pubmed {:search-term search-term
                                  :search-count (count pmids)})]
        (import/import-source-impl
         project-id source-meta
         {:article-refs pmids, :get-articles pubmed-get-articles}
         :use-future? use-future? :threads threads)))))
