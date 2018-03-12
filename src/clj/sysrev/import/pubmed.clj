(ns sysrev.import.pubmed
  (:require [sysrev.db.core :refer
             [do-query do-execute with-transaction clear-project-cache to-jsonb *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.db.sources :as sources]
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
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.xml :as dxml]))

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

(defn parse-pmid-xml [pxml]
  (let [title (xml-find-value pxml [:MedlineCitation :Article :ArticleTitle])
        journal (xml-find-value pxml [:MedlineCitation :Article :Journal :Title])
        abstract (-> (xml-find pxml [:MedlineCitation :Article :Abstract :AbstractText]) parse-abstract)
        authors (-> (xml-find pxml [:MedlineCitation :Article :AuthorList :Author])
                    parse-pubmed-author-names)
        pmid (xml-find-value pxml [:MedlineCitation :PMID])
        keywords (xml-find-vector pxml [:MedlineCitation :KeywordList :Keyword])
        locations (extract-article-location-entries pxml)
        year (-> (xml-find [pxml] [:MedlineCitation :Article :Journal :JournalIssue :PubDate :Year])
                 first :content first parse-integer)
        month (-> (xml-find [pxml] [:MedlineCitation :Article :Journal :JournalIssue :PubDate :Month])
                  first :content first)
        day (-> (xml-find [pxml] [:MedlineCitation :Article :Journal :JournalIssue :PubDate :Day])
                first :content first)]
    {:raw (dxml/emit-str pxml)
     :remote-database-name "MEDLINE"
     :primary-title title
     :secondary-title journal
     :abstract abstract
     :authors authors
     :year year
     :keywords keywords
     :public-id (str pmid)
     :locations locations
     :date (str year " " month " " day)
     }))

(defn fetch-pmids-xml [pmids]
  (-> (str "https://eutils.ncbi.nlm.nih.gov"
           "/entrez/eutils/efetch.fcgi"
           (format "?db=pubmed&id=%s&retmode=xml"
                   (str/join "," pmids)))
      http/get
      :body))

(defn fetch-pmid-entries [pmids]
  (->> (fetch-pmids-xml pmids)
       parse-xml-str :content
       (mapv parse-pmid-xml)))

(defn fetch-pmid-entry [pmid]
  (first (fetch-pmid-entries [pmid])))

;; https://www.ncbi.nlm.nih.gov/books/NBK25500/#chapter1.Searching_a_Database
(defn get-search-query
  "Given a query and retstart value, fetch the json associated with that query. Return a EDN map of that data. A page size is 20 PMIDs and starts on page 1"
  [query retmax retstart]
  (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                {:query-params {"db" "pubmed"
                                "term" query
                                "retmode" "json"
                                "retmax" retmax
                                "retstart" retstart}})
      :body
      (json/read-str :key-fn keyword)))

(defn get-search-query-response
  "Given a query and page number, return a EDN map corresponding to a JSON response. A page size is 20 PMIDs and starts on page 1"
  [query page-number]
  (let [retmax 20
        esearch-result (:esearchresult (get-search-query query retmax (* (- page-number 1) retmax)))]
    (if (:ERROR esearch-result)
      {:pmids []
       :count 0}
      {:pmids (mapv #(Integer/parseInt %)
                    (-> esearch-result
                        :idlist))
       :count (Integer/parseInt (-> esearch-result
                                    :count))})))

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

(defn get-all-pmids-for-query
  "Given a search query, return all PMIDs as a vector of integers"
  [query]
  (let [total-pmids (:count (get-search-query-response query 1))
        retmax 100000
        max-pages (int (Math/ceil (/ total-pmids retmax)))]
    (vec (apply concat
                (mapv
                 (fn [page]
                   (mapv (fn [string]
                           (Integer/parseInt string))
                         (get-in (get-search-query query retmax (* page retmax))
                                 [:esearchresult :idlist])))
                 (vec (range 0 max-pages)))))))

(defn- add-article [article project-id]
  (try
    (articles/add-article article project-id *conn*)
    (catch Throwable e
      (log/info (str "exception in sysrev.import.pubmed/add-article: "
                     (.getMessage e)))
      nil)))

(defn- import-pmids-to-project
  "Imports into project all articles referenced in list of PubMed IDs.
  Note that this will not import an article if the PMID already exists
  in the project."
  [pmids project-id source-id]
  (try
    (doseq [pmids-group (->> pmids (partition-all 20))]
      (doseq [article (->> pmids-group fetch-pmid-entries (remove nil?))]
        (try
          (with-transaction
            (let [existing-articles
                  (-> (select :article-id)
                      (from :article)
                      (where
                       [:and
                        [:= :project-id project-id]
                        [:= :public-id (:public-id article)]])
                      do-query)]
              (if (not-empty existing-articles)
                ;; if PMID is already present in project, just mark the
                ;; existing article(s) as contained in this source
                (doseq [{:keys [article-id]} existing-articles]
                  (sources/add-article-to-source! article-id source-id))
                ;; otherwise add new article
                (when-let [article-id (add-article
                                       (-> article
                                           (dissoc :locations)
                                           (assoc :enabled false))
                                       project-id)]
                  (sources/add-article-to-source! article-id source-id)
                  (when (not-empty (:locations article))
                    (-> (sqlh/insert-into :article-location)
                        (values
                         (->> (:locations article)
                              (mapv #(assoc % :article-id article-id))))
                        do-execute))))))
          (catch Throwable e
            (log/info (format "error importing pmid #%s"
                              (:public-id article))
                      ": " (.getMessage e))))))
    true
    (catch Throwable e
      (log/info (str "error in import-pmids-to-project: "
                     (.getMessage e)))
      false)
    (finally
      (clear-project-cache project-id))))

(defn import-pmids-to-project-with-meta!
  "Import articles into project-id using the meta map as a source description. If the optional keyword :use-future? true is used, then the importing is wrapped in a future"
  [pmids project-id meta & {:keys [use-future? threads]
                            :or {use-future? false threads 1}}]
  (let [source-id (sources/create-project-source-metadata!
                   project-id (assoc meta :importing-articles? true))]
    (if (and use-future? (nil? *conn*))
      (future
        (try
          (let [thread-groups
                (->> pmids
                     (partition-all (max 1 (quot (count pmids) threads))))
                thread-results
                (->> thread-groups
                     (mapv
                      (fn [thread-pmids]
                        (future
                          (try
                            (import-pmids-to-project
                             thread-pmids project-id source-id)
                            (catch Throwable e
                              (log/info "Error in import-pmids-to-project-with-meta! (inner future)"
                                        (.getMessage e))
                              false)))))
                     (mapv deref))
                success? (every? true? thread-results)]
            (if success?
              (do
                (sources/update-project-source-metadata!
                 source-id (assoc meta :importing-articles? false)))
              (sources/fail-project-source-import! source-id))
            success?)
          (catch Throwable e
            (log/info "Error in import-pmids-to-project-with-meta! (outer future)"
                      (.getMessage e))
            (sources/fail-project-source-import! source-id)
            false)
          (finally
            ;; update the enabled flag for the articles
            (sources/update-project-articles-enabled! project-id))))
      (try
        ;; import the data
        (let [success?
              (import-pmids-to-project pmids project-id source-id)]
          (if success?
            (sources/update-project-source-metadata!
             source-id (assoc meta :importing-articles? false))
            (sources/fail-project-source-import! source-id))
          success?)
        (catch Throwable e
          (log/info "Error in import-pmids-to-project-with-meta!"
                    (.getMessage e))
          (sources/fail-project-source-import! source-id)
          false)
        (finally
          ;; update the enabled flag for the articles
          (sources/update-project-articles-enabled! project-id))))))

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

(defn parse-pmid-file
  "Loads a list of integer PubMed IDs from a file. PMIDs can be separated by commas and white space. Removes duplicates"
  [file]
  (try (->> (-> (slurp file)
                (string/split #"(\s+|,)"))
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
  (import-pmids-to-project (load-pmids-file path) project-id))
