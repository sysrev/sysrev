(ns sysrev.source.pubmed
  (:require [sysrev.config.core :as config]
            [sysrev.pubmed :as pubmed]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defn pubmed-get-articles [pmids]
  (->> (sort pmids)
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
  [stype project-id {:keys [search-term]} {:keys [use-future? threads] :as options}]
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
      (let [source-meta (source/make-source-meta
                         :pubmed {:search-term search-term
                                  :search-count pmids-count})]
        (import-source-impl
         project-id source-meta
         {:get-article-refs #(pubmed/get-all-pmids-for-query search-term),
          :get-articles pubmed-get-articles}
         options)))))
