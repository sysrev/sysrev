(ns sysrev.source.ctgov
  (:require [sysrev.config :as config]
            [sysrev.formats.ctgov :as ctgov]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod make-source-meta :ctgov [_ {:keys [search-term results-count]}]
  {:source "CT.gov search"
   :search-term search-term
   :results-count results-count})

(defn get-ctgov-studies [coll]
  (into [] (for [{:keys [NCTId BriefTitle]} coll]
             {:external-id (first NCTId)
              :primary-title (first BriefTitle)})))

(defmethod import-source :ctgov [_ project-id {:keys [search-term]} {:as options}]
  (assert (string? search-term))
  (let [{:keys [max-import-articles]} config/env
        studies-count (:count (ctgov/search search-term 1))
        source-exists? (->> (source/project-sources project-id)
                            (filter #(and (= (get-in % [:meta :search-term]) search-term)
                                          (= (get-in % [:meta :source]) "CT.gov search"))))]
    (cond (seq source-exists?)
          {:error {:message (format "Source already exists for %s" search-term)}}
          (> studies-count max-import-articles)
          {:error {:message (format "Too many results from search (max %d; got %d)"
                                    max-import-articles studies-count)}}
          :else (let [source-meta (source/make-source-meta
                                   :ctgov {:search-term search-term
                                           :results-count studies-count})]
                  (import-source-impl
                   project-id source-meta
                   {:types {:article-type "json"
                            :article-subtype "ctgov"}
                    :get-article-refs #(ctgov/get-nctids-for-query search-term)
                    :get-articles get-ctgov-studies}
                   options)))))
