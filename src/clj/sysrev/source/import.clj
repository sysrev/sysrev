(ns sysrev.source.import
  (:require [sysrev.source.interface :refer [import-source]]
            (sysrev.source pubmed pmid endnote pdf-zip extra)))

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

(defn import-article-text-manual
  [project-id {:keys [articles] :as input} & [{:as options}]]
  (import-source :api-text-manual project-id input options))

(defn import-pdf-zip
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :pdf-zip project-id input options))

(defn import-ris
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :ris project-id input options))
