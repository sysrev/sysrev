(ns sysrev.import.pubmed
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.data.xml :as dxml]
            [clojure-csv.core :as csv]
            [clj-http.client :as http]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction clear-project-cache to-jsonb *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]
            [sysrev.source.pubmed :as src-pubmed]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.biosource.importance :as importance]
            [sysrev.pubmed :as pm]
            [sysrev.util :as u]
            [sysrev.shared.util :as su :refer [in? map-values]]))

(defn import-pmids-to-project-with-meta!
  "Import articles into project-id using the meta map as a source description.

  If the optional keyword :use-future? true is used, then the
  importing is wrapped in a future"
  [pmids project-id meta & {:keys [use-future? threads]
                            :or {use-future? false threads 1}}]
  (let [source-id (source/create-source
                   project-id (assoc meta :importing-articles? true))]
    (try
      (let [success? (import/import-source
                      project-id source-id
                      {:article-refs pmids
                       :get-articles src-pubmed/pubmed-get-articles}
                      :use-future? use-future? :threads threads)]
        (if success? true
            (do (source/fail-source-import source-id)
                false))))))

(defn load-pmids-file
  "Loads a list of integer PubMed IDs from a linebreak-separated text file."
  [path]
  (->> path io/file io/reader
       csv/parse-csv
       (mapv (comp #(Integer/parseInt %) first))))

(defn parse-pmid-file
  "Loads a list of integer PubMed IDs from a file. PMIDs can be separated by commas and white space. Removes duplicates"
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

(defn import-from-pmids-file
  "Imports articles from PubMed API into project from linebreak-separated text
  file of PMIDs."
  [project-id path]
  nil
  #_ (import-pmids-to-project (load-pmids-file path) project-id))
