(ns sysrev.source.import
  (:require sysrev.source.ctgov
            sysrev.source.datasource
            sysrev.source.endnote
            sysrev.source.extra
            sysrev.source.fda-drugs-docs
            [sysrev.source.interface :refer [import-source]]
            sysrev.source.json
            sysrev.source.pdf-zip
            sysrev.source.pdfs
            sysrev.source.pmid
            sysrev.source.project-filter
            sysrev.source.pubmed
            sysrev.source.ris))

(defn import-pubmed-search
  [request project-id {:keys [search-term] :as input} & [{:as options}]]
  (import-source request :pubmed project-id input options))

(defn import-pmid-file
  [request project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source request :pmid-file project-id input options))

(defn import-pmid-vector
  [request project-id {:keys [pmids] :as input} & [{:as options}]]
  (import-source request :pmid-vector project-id input options))

(defn import-endnote-xml
  [request project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source request :endnote-xml project-id input options))

(defn import-article-text-manual
  [request project-id {:keys [articles] :as input} & [{:as options}]]
  (import-source request :api-text-manual project-id input options))

(defn import-pdfs
  [request project-id {:keys [files]} & [{:as options}]]
  (import-source request :pdfs project-id files options))

(defn import-pdf-zip
  [request project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source request :pdf-zip project-id input options))

(defn import-json
  [request project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source request :json project-id input options))

;; be sure to add entry above to require in sysrev.source
;; if new data type, add to
;; sysrev.datasource.core/project-source-meta->article-type
;; and an enrich-articles method in sysrev.datasource.api
(defn import-ris
  [request project-id {:keys [file filename] :as input} & [{:as options}]]
  (import-source request :ris project-id input options))

(defn import-ctgov-search
  [request project-id {:keys [search-term] :as input} & [{:as options}]]
  (import-source request :ctgov project-id input options))

(defn import-fda-drugs-docs-search
  [request project-id {:keys [search-term] :as input} & [{:as options}]]
  (import-source request :fda-drugs-docs project-id input options))
