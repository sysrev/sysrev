(ns sysrev.source.import
  (:require [sysrev.source.interface :refer [import-source]]
            sysrev.source.pubmed
            sysrev.source.pmid
            sysrev.source.endnote
            sysrev.source.pdf-zip
            sysrev.source.pdfs
            sysrev.source.extra
            sysrev.source.ris
            sysrev.source.ctgov
            sysrev.source.datasource
            sysrev.source.project-filter))

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

(defn import-pdfs
  [project-id {:keys [files]} & [{:as options}]]
  (import-source :pdfs project-id files options))

(defn import-pdf-zip
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :pdf-zip project-id input options))

;; be sure to add entry above to require in sysrev.source
;; if new data type, add to
;; sysrev.datasource.core/project-source-meta->article-type
;; and an enrich-articles method in sysrev.datasource.api
(defn import-ris
  [project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source :ris project-id input options))

(defn import-ctgov-search
  [project-id {:keys [search-term] :as input} & [{:as options}]]
  (import-source :ctgov project-id input options))

(defn ^:unused import-datasource-query
  [project-id {:keys [query entities] :as input} & [{:as options}]]
  (import-source :datasource-query project-id input options))

(defn ^:unused import-datasource
  [project-id {:keys [datasource-id datasource-name entities] :as input} & [{:as options}]]
  (import-source :datasource project-id input options))

(defn ^:unused import-dataset
  [project-id {:keys [dataset-id dataset-name entities] :as input} & [{:as options}]]
  (import-source :dataset project-id input options))

(defn ^:unused import-project-filter
  [project-id {:keys [url-filter source-project-id] :as input} & [{:as options}]]
  (import-source :project-filter project-id input options))
