(ns sysrev.import.pubmed
  (:require [sysrev.db.core :refer
             [do-query do-execute do-transaction clear-project-cache *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.util :refer
             [parse-xml-str parse-integer
              xml-find xml-find-value xml-find-vector]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clj-http.client :as http]
            [clojure-csv.core :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]))

(defn fetch-pmid-xml [pmid]
  (-> (str "https://eutils.ncbi.nlm.nih.gov"
           "/entrez/eutils/efetch.fcgi"
           (format "?db=pubmed&id=%d&retmode=xml" pmid))
      http/get
      :body))

;; https://www.ncbi.nlm.nih.gov/books/NBK25500/#chapter1.Searching_a_Database
(defn get-query
  "Given a query and page number, fetch the json associated with that query. Return a EDN map of that data. A page size is 20 PMIDs and starts on page 1"
  [query page]
  (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                {:query-params {"db" "pubmed"
                                "term" query
                                "retmode" "json"
                                "retmax" "20"
                                "retstart" (* (- page 1) 20)
                                }})
      :body
      (json/read-str :key-fn keyword)))

(defn get-query-pmids
  "Given a query and page number, return a vector of the associated pmid integers. A page size is 20 PMIDs and starts on page 1"
  [query page]
  (mapv #(Integer/parseInt %)
        (-> query
            (get-query page)
            :esearchresult
            :idlist)))

(defn get-pmids-summary
  "Given a vector of PMIDs, return the summaries as a map"
  [pmids]
  (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                {:query-params {"db" "pubmed"
                                "retmode" "json"
                                "id" (clojure.string/join "," pmids)}})
      :body
      (json/read-str :key-fn (fn [item] (if (int? (read-string item))
                                          (read-string item)
                                          (keyword item))))
      :result))

(defn parse-pubmed-author-names [authors]
  (when-not (empty? authors)
    (->> authors
         (mapv
          (fn [entry]
            (let [last-name (xml-find-value entry [:LastName])
                  initials (xml-find-value entry [:Initials])]
              (if (string? initials)
                (str last-name ", " (->> initials
                                         (#(string/split % #" "))
                                         (map #(str % "."))
                                         (string/join " ")))
                last-name))))
         (filterv string?))))

(defn extract-article-location-entries
  "Extracts entries for article_location from parsed PubMed API XML article."
  [pxml]
  (distinct
   (concat
    (->> (xml-find pxml [:MedlineCitation :Article :ELocationID])
         (map (fn [{tag :tag
                    {source :EIdType} :attrs
                    content :content}]
                (map #(do {:source source
                           :external-id %})
                     content)))
         (apply concat))
    (->> (xml-find pxml [:PubmedData :ArticleIdList :ArticleId])
         (map (fn [{tag :tag
                    {source :IdType} :attrs
                    content :content}]
                (map #(do {:source source
                           :external-id %})
                     content)))
         (apply concat)))))

(defn parse-abstract [abstract-texts]
  (when-not (empty? abstract-texts)
    (let [sections (map (fn [sec]
                          {:header  (-> sec :attrs :Label)
                           :content (:content sec)})
                        abstract-texts)
          parse-section (fn [section]
                          (let [header (:header section)
                                content (-> section :content first)]
                            (if-not (empty? header)
                              (str header ": " content)
                              content)))
          paragraphs (map parse-section sections)]
      (string/join "\n\n" paragraphs))))

(defn parse-pmid-xml [xml-str]
  (let [pxml (-> xml-str parse-xml-str :content first)
        title (xml-find-value pxml [:MedlineCitation :Article :ArticleTitle])
        journal (xml-find-value pxml [:MedlineCitation :Article :Journal :Title])
        abstract (-> (xml-find pxml [:MedlineCitation :Article :Abstract :AbstractText]) parse-abstract)
        authors (-> (xml-find pxml [:MedlineCitation :Article :AuthorList :Author])
                    parse-pubmed-author-names)
        pmid (xml-find-value pxml [:MedlineCitation :PMID])
        keywords (xml-find-vector pxml [:MedlineCitation :KeywordList :Keyword])
        locations (extract-article-location-entries pxml)
        year (-> (xml-find [pxml] [:MedlineCitation :Article :ArticleDate :Year])
                 first :content first parse-integer)]
    {:raw xml-str
     :remote-database-name "MEDLINE"
     :primary-title title
     :secondary-title journal
     :abstract abstract
     :authors authors
     :year year
     :keywords keywords
     :public-id (str pmid)
     :locations locations}))

(defn fetch-pmid-entry [pmid]
  (try
    (-> pmid fetch-pmid-xml parse-pmid-xml)
    (catch Throwable e
      (println (format "exception in (fetch-pmid-entry %s)" pmid))
      (println (.getMessage e))
      nil)))

(defn- add-article [article project-id]
  (try
    (articles/add-article article project-id *conn*)
    (catch Throwable e
      (println "exception in add-format")
      (println "article:")
;;      (println (pr-str article))
      (println "error:")
      (println (.getMessage e))
      nil)))

(defn import-pmids-to-project
  "Imports into project all articles referenced in list of PubMed IDs."
  [pmids project-id]
  (try
    (doseq [pmid pmids]
      (try
        ;; skip article if already loaded in project
        (when-not (project/project-contains-public-id (str pmid) project-id)
          (log/info "importing project article #"
                    (project/project-article-count project-id))
          (when-let [article (fetch-pmid-entry pmid)]
            (doseq [[k v] article]
              (when (or (nil? v)
                        (and (coll? v) (empty? v)))
                (log/debug (format "* field `%s` is empty" (pr-str k)))))
            (when-let [article-id (add-article
                                   (dissoc article :locations)
                                   project-id)]
              (when (not-empty (:locations article))
                (-> (sqlh/insert-into :article-location)
                    (values
                     (->> (:locations article)
                          (mapv #(assoc % :article-id article-id))))
                    do-execute)))))
        (catch Throwable e
          (println (format "error importing pmid #%s" pmid)))))
    (finally
      (clear-project-cache project-id))))

(defn reload-project-abstracts [project-id]
  (let [articles
        (-> (select :article-id :raw)
            (from [:article :a])
            (where
             [:and
              [:!= :raw nil]
              [:= :project-id project-id]])
            do-query)]
    (->>
     articles
     (pmap
      (fn [{:keys [article-id raw]}]
        (let [pxml (-> raw parse-xml-str :content first)
              abstract
              (-> (xml-find
                   pxml [:MedlineCitation :Article :Abstract :AbstractText])
                  parse-abstract)]
          (when-not (empty? abstract)
            (-> (sqlh/update :article)
                (sset {:abstract abstract})
                (where [:= :article-id article-id])
                do-execute))
          (println (str "processed #" article-id)))))
     doall)
    (println (str "updated " (count articles) " articles"))))

(defn load-pmids-file
  "Loads a list of integer PubMed IDs from a linebreak-separated text file."
  [path]
  (->> path io/file io/reader
       csv/parse-csv
       (mapv (comp #(Integer/parseInt %) first))))

(defn import-from-pmids-file
  "Imports articles from PubMed API into project from linebreak-separated text
  file of PMIDs."
  [project-id path]
  (import-pmids-to-project (load-pmids-file path) project-id))

;; Used to import project from PMID list file
#_
(let [{:keys [project-id]} (create-project "Tox21")]
  (-> "/insilica/tox21-pmids.txt"
      load-pmids-file
      (import-pmids-to-project 102)))
