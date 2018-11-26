(ns sysrev.source.pmid
  (:require [clojure-csv.core :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.config.core :as config]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import :refer [import-source]]
            [sysrev.source.pubmed :refer [pubmed-get-articles]]))

(defn parse-pmid-file
  "Loads a list of integer PubMed IDs from a file. PMIDs can be
  separated by commas and white space. Removes duplicates"
  [file]
  (try (->> (-> (slurp file)
                (str/split #"(\s+|,)"))
            (filterv (comp not empty?))
            (mapv (comp #(Integer/parseInt %)))
            distinct
            (apply vector))
       (catch Throwable e
         (log/info "Bad Format " (.getMessage e))
         nil)))

(defn- pmid-source-exists? [project-id filename]
  (->> (source/project-sources project-id)
       (filter #(= (get-in % [:meta :filename]) filename))
       not-empty))

(defmethod import-source :pmid-file
  [project-id stype {:keys [file filename]}
   {:keys [use-future? threads] :or {use-future? true threads 1}}]
  (let [{:keys [max-import-articles]} config/env
        pmids (parse-pmid-file file)]
    (cond
      (empty? pmids)
      {:error "Unable to parse file"}

      (pmid-source-exists? project-id filename)
      {:error (format "Source already exists for %s" (pr-str filename))}

      (> (count pmids) max-import-articles)
      {:error (format "Too many PMIDs from file (max %d; got %d)"
                      max-import-articles (count pmids))}

      :else
      (let [source-meta (source/make-source-meta
                         :pmid-file {:filename filename})]
        (import/import-source-impl
         project-id source-meta
         {:article-refs pmids, :get-articles pubmed-get-articles}
         :use-future? use-future? :threads threads)))))

(defmethod import-source :pmid-vector
  [project-id stype {:keys [pmids]}
   {:keys [use-future? threads] :or {use-future? true threads 1}}]
  (let [{:keys [max-import-articles]} config/env]
    (cond
      (empty? pmids)
      {:error "pmids list is empty"}

      (> (count pmids) max-import-articles)
      {:error (format "Too many PMIDs requested (max %d; got %d)"
                      max-import-articles (count pmids))}

      :else
      (let [source-meta (source/make-source-meta :pmid-vector {})]
        (import/import-source-impl
         project-id source-meta
         {:article-refs pmids, :get-articles pubmed-get-articles}
         :use-future? use-future? :threads threads)))))
