(ns sysrev.source.pmid
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.config :as config]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source import-source-impl]]
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
  [sr-context _ project-id {:keys [file filename]} {:as options}]
  (let [{:keys [max-import-articles]} config/env
        pmids (parse-pmid-file file)]
    (cond
      (empty? pmids)
      {:error {:message "Unable to parse file"}}

      (pmid-source-exists? project-id filename)
      {:error {:message (format "Source already exists for %s" (pr-str filename))}}

      (> (count pmids) max-import-articles)
      {:error {:message (format "Too many PMIDs from file (max %d; got %d)"
                                max-import-articles (count pmids))}}

      :else
      (let [source-meta {:source "PMID file" :filename filename}]
        (import-source-impl
         sr-context project-id source-meta
         {:types {:article-type "academic" :article-subtype "pubmed"}
          :get-article-refs (constantly pmids)
          :get-articles pubmed-get-articles}
         options)))))

(defmethod import-source :pmid-vector
  [sr-context _ project-id {:keys [pmids]} {:as options}]
  (let [{:keys [max-import-articles]} config/env]
    (cond
      (empty? pmids)
      {:error {:message "pmids list is empty"}}

      (> (count pmids) max-import-articles)
      {:error {:message (format "Too many PMIDs sr-contexted (max %d; got %d)"
                                max-import-articles (count pmids))}}

      :else
      (let [source-meta {:source "PMID vector"}]
        (import-source-impl
         sr-context project-id source-meta
         {:types {:article-type "academic" :article-subtype "pubmed"}
          :get-article-refs (constantly pmids)
          :get-articles pubmed-get-articles}
         options)))))
