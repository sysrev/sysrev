(ns sysrev.import.pubmed
  (:require [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.util :refer
             [parse-xml-str parse-integer
              xml-find xml-find-value xml-find-vector ]]
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
  (->> authors
       (mapv
        (fn [entry]
          (let [last-name (xml-find-value entry [:LastName])
                initials (xml-find-value entry [:Initials])]
            (str last-name ", " (->> initials
                                     (#(str/split % #" "))
                                     (map #(str % "."))
                                     (str/join " "))))))))

(defn fetch-pmid-entry [pmid]
  (try
    (let [xml-str (fetch-pmid-xml pmid)
          pxml (-> xml-str parse-xml-str :content first)
          title (xml-find-value pxml [:MedlineCitation :Article :ArticleTitle])
          journal (xml-find-value pxml [:MedlineCitation :Article :Journal :Title])
          abstract (xml-find-value pxml [:MedlineCitation :Article :Abstract :AbstractText])
          authors (-> (xml-find pxml [:MedlineCitation :Article :AuthorList :Author])
                      parse-pubmed-author-names)
          keywords (xml-find-vector pxml [:MedlineCitation :KeywordList :Keyword])
          elocation-ids (-> (xml-find [pxml] [:MedlineCitation :Article :ELocationID]))
          year (-> (xml-find [pxml] [:MedlineCitation :Article :ArticleDate :Year])
                   first :content first parse-integer)]
      (assert (not (empty? title)))
      {:raw xml-str
       :remote-database-name "MEDLINE"
       :primary-title title
       :secondary-title journal
       :abstract abstract
       :authors authors
       :year year
       :keywords keywords
       :public-id (str pmid)})
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
        (articles/add-article article project-id)))))

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
