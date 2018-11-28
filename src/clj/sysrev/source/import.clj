(ns sysrev.source.import
  (:require [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source]]
            (sysrev.source pubmed pmid endnote)))

(defn import-pubmed-search
  [project-id {:keys [search-term] :as input} & [{:as options}]]
  (import-source :pubmed project-id input options))

(defn import-pmid-file
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :pmid-file project-id input options))

(defn import-pmid-vector
  [project-id {:keys [pmids] :as input} & [{:as options}]]
  (import-source :pmid-vector project-id input options))

(defn import-endnote-xml
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :endnote-xml project-id input options))
