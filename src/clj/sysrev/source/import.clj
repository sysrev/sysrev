(ns sysrev.source.import
  (:require [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source]]
            [sysrev.source.pubmed :as s-pubmed]
            [sysrev.source.pmid :as s-pmid]))

(defn import-pubmed-search
  [project-id {:keys [search-term] :as input} & [{:as options}]]
  (import-source :pubmed project-id input options))

(defn import-pmid-file
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :pmid-file project-id input options))

(defn import-pmid-vector
  [project-id {:keys [pmids] :as input} & [{:as options}]]
  (import-source :pmid-vector project-id input options))
