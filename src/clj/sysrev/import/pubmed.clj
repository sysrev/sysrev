(ns sysrev.import.pubmed
  (:require [sysrev.db.core :refer [do-query do-execute]]
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
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn fetch-pmid-xml [pmid]
  (-> (str "https://eutils.ncbi.nlm.nih.gov"
           "/entrez/eutils/efetch.fcgi"
           (format "?db=pubmed&id=%d&retmode=xml" pmid))
      http/get
      :body))

(defn parse-pubmed-author-names [authors]
  (when-not (empty? authors)
    (->> authors
         (mapv
          (fn [entry]
            (let [last-name (xml-find-value entry [:LastName])
                  initials (xml-find-value entry [:Initials])]
              (if (string? initials)
                (str last-name ", " (->> initials
                                         (#(str/split % #" "))
                                         (map #(str % "."))
                                         (str/join " ")))
                last-name)))))))

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
      (str/join "\n\n" paragraphs))))

(defn parse-pmid-xml [xml-str]
  (let [pxml (-> xml-str parse-xml-str :content first)
        title (xml-find-value pxml [:MedlineCitation :Article :ArticleTitle])
        journal (xml-find-value pxml [:MedlineCitation :Article :Journal :Title])
        abstract (-> (xml-find pxml [:MedlineCitation :Article :Abstract :AbstractText]) parse-abstract)
        authors (-> (xml-find pxml [:MedlineCitation :Article :AuthorList :Author])
                    parse-pubmed-author-names)
        pmid (xml-find-value pxml [:PMID])
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
      (println (.getMessage e)))))

(defn import-pmids-to-project
  "Imports into project all articles referenced in list of PubMed IDs."
  [pmids project-id]
  (doseq [pmid pmids]
    ;; skip article if already loaded in project
    (when-not (project/project-contains-public-id pmid project-id)
      (println (str "importing project article #"
                    (project/project-article-count project-id)))
      (when-let [article (fetch-pmid-entry pmid)]
        (doseq [[k v] article]
          (when (or (nil? v)
                    (and (coll? v) (empty? v)))
            (println (format "* field `%s` is empty" (pr-str k)))))
        (when-let [article-id
                   (->> (articles/add-article
                         (dissoc article :locations) project-id)
                        first :article-id)]
          (when (not-empty (:locations article))
            (-> (sqlh/insert-into :article-location)
                (values
                 (->> (:locations article)
                      (mapv #(assoc % :article-id article-id))))
                do-execute)))))))

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

;; Used to import project from PMID list file
#_
(let [{:keys [project-id]} (create-project "Tox21")]
  (-> "/insilica/tox21-pmids.txt"
      load-pmids-file
      (import-pmids-to-project 102)))
