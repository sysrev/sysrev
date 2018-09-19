(ns sysrev.import.pubmed
  (:require [sysrev.db.core :refer
             [do-query do-execute with-transaction clear-project-cache to-jsonb *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.db.sources :as sources]
            [sysrev.cassandra :as cdb]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.biosource.importance :as importance]
            [sysrev.config.core :refer [env]]
            [sysrev.util :as util :refer
             [parse-xml-str xml-find xml-find-value xml-find-vector]]
            [sysrev.shared.util :refer [parse-integer]]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [me.raynes.fs :as fs]
            [miner.ftp :as ftp]
            [clj-http.client :as http]
            [clojure-csv.core :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.xml :as dxml]))

(def use-cassandra-pubmed? false)

(def e-util-api-key (:e-util-api-key env))

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
  [pxml & [insilica?]]
  (distinct
   (concat
    (->> (if insilica?
           (xml-find pxml [:Article :ELocationID])
           (xml-find pxml [:MedlineCitation :Article :ELocationID]))
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

(defn parse-html-text-content [content]
  (when content
    (->> content
         (map
          (fn [txt]
            (if (and (map? txt) (:tag txt))
              ;; Handle embedded HTML
              (when (:content txt)
                (str "<" (name (:tag txt)) ">"
                     (str/join (:content txt))
                     "</" (name (:tag txt)) ">"))
              txt)))
         (filter string?)
         (map str/trim)
         (str/join))))

(defn parse-abstract [abstract-texts]
  (try
    (when (not-empty abstract-texts)
      (let [sections
            (map (fn [sec]
                   {:header  (some-> sec :attrs :Label str/trim)
                    :content (some-> sec :content parse-html-text-content)})
                 abstract-texts)
            parse-section (fn [{:keys [header content]}]
                            (if (not-empty header)
                              (str header ": " content)
                              content))
            paragraphs (map parse-section sections)]
        (string/join "\n\n" paragraphs)))
    (catch Throwable e
      (log/error "parse-abstract error:" (.getMessage e))
      (log/error "abstract-texts =" (pr-str abstract-texts))
      (throw e))))

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
     :date (str year " " month " " day)}))

(defn parse-pmid-xml-insilica [pxml]
  (let [title (-> (xml-find pxml [:Article :ArticleTitle])
                  first :content parse-html-text-content)
        journal (-> (xml-find pxml [:Article :Journal :Title])
                    first :content parse-html-text-content)
        abstract (-> (xml-find pxml [:Article :Abstract :AbstractText]) parse-abstract)
        authors (-> (xml-find pxml [:Article :AuthorList :Author])
                    parse-pubmed-author-names)
        pmid (xml-find-value pxml [:PMID])
        keywords (xml-find-vector pxml [:KeywordList :Keyword])
        locations (extract-article-location-entries pxml true)
        year (or (-> (xml-find [pxml] [:Article :Journal :JournalIssue :PubDate :Year])
                     first :content first parse-integer)
                 (-> (xml-find [pxml] [:DateCompleted :Year])
                     first :content first parse-integer))
        month (or (-> (xml-find [pxml] [:Article :Journal :JournalIssue :PubDate :Month])
                      first :content first)
                  (-> (xml-find [pxml] [:DateCompleted :Month])
                      first :content first parse-integer))
        day (or (-> (xml-find [pxml] [:Article :Journal :JournalIssue :PubDate :Day])
                    first :content first)
                (-> (xml-find [pxml] [:DateCompleted :Day])
                    first :content first parse-integer))]
    {:remote-database-name "MEDLINE"
     :primary-title title
     :secondary-title journal
     :abstract abstract
     :authors (mapv str/trim authors)
     :year year
     :keywords keywords
     :public-id (str pmid)
     :locations locations
     :date (str year " " month " " day)}))

(defn fetch-pmids-xml [pmids]
  (util/wrap-retry
   (fn []
     (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi"
                   {:query-params {"db" "pubmed"
                                   "id" (str/join "," pmids)
                                   "retmode" "xml"
                                   "api_key" e-util-api-key}})
         #_(format "?db=pubmed&id=%s&retmode=xml"
                   (str/join "," pmids))
         :body))
   :fname "fetch-pmids-xml" :throttle-delay 100))

#_
(defn fetch-pmids-xml-insilica [pmids]
  (-> (http/post "https://api.insilica.co/datasource/pubmed/getEntities"
                 {:query-params {"ids" (str
                                        "["
                                        (str/join "," (->> pmids (map str) (map pr-str)))
                                        "]")}})
      #_(format "?db=pubmed&id=%s&retmode=xml"
                (str/join "," pmids))
      :body))

(defn fetch-pmid-entries [pmids]
  (->> (fetch-pmids-xml pmids)
       parse-xml-str :content
       (mapv parse-pmid-xml)))

(defn fetch-pmid-entries-insilica [pmids]
  (->> (cdb/get-pmids-xml pmids)
       (pmap #(some-> % parse-xml-str parse-pmid-xml-insilica
                      (merge {:raw %})))
       vec))

(defn fetch-pmid-entry [pmid]
  (first (fetch-pmid-entries [pmid])))

;; https://www.ncbi.nlm.nih.gov/books/NBK25500/#chapter1.Searching_a_Database
(defn get-search-query
  "Given a query and retstart value, fetch the json associated with that query. Return a EDN map of that data. A page size is 20 PMIDs and starts on page 1"
  [query retmax retstart]
  (util/wrap-retry
   (fn []
     (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                   {:query-params {"db" "pubmed"
                                   "term" query
                                   "retmode" "json"
                                   "retmax" retmax
                                   "retstart" retstart
                                   "api_key" e-util-api-key}})
         :body
         (json/read-str :key-fn keyword)))
   :fname "get-search-query"))

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
  (util/wrap-retry
   (fn []
     (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                   {:query-params {"db" "pubmed"
                                   "retmode" "json"
                                   "id" (clojure.string/join "," pmids)
                                   "api_key" e-util-api-key}})
         :body
         (json/read-str :key-fn (fn [item] (if (int? (read-string item))
                                             (read-string item)
                                             (keyword item))))
         :result))
   :fname "get-pmids-summary"))

(defn get-all-pmids-for-query
  "Given a search query, return all PMIDs as a vector of integers"
  [query]
  (let [total-pmids (:count (get-search-query-response query 1))
        retmax (:max-import-articles env)
        max-pages (int (Math/ceil (/ total-pmids retmax)))]
    (->> (range 0 max-pages)
         (mapv
          (fn [page]
            (mapv #(Integer/parseInt %)
                  (get-in (get-search-query query retmax (* page retmax))
                          [:esearchresult :idlist]))))
         (apply concat)
         vec)))

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
    (doseq [pmids-group (->> pmids (partition-all (if use-cassandra-pubmed? 200 40)))]
      (doseq [article (->> pmids-group
                           (#(if use-cassandra-pubmed?
                               (fetch-pmid-entries-insilica %)
                               (fetch-pmid-entries %)))
                           (remove nil?))]
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
      (.printStackTrace e)
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
        (let [success?
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
                           (mapv deref))]
                  (every? true? thread-results))
                (catch Throwable e
                  (log/info "Error in import-pmids-to-project-with-meta! (outer future)"
                            (.getMessage e))
                  false))]
          (with-transaction
            ;; update source metadata
            (if success?
              (sources/update-project-source-metadata!
               source-id (assoc meta :importing-articles? false))
              (sources/fail-project-source-import! source-id))
            ;; update the enabled flag for the articles
            (sources/update-project-articles-enabled! project-id))
          ;; start threads for updates from api.insilica.co
          (when success?
            (predict-api/schedule-predict-update project-id)
            (importance/schedule-important-terms-update project-id))
          success?))
      (let [success?
            (try
              (import-pmids-to-project pmids project-id source-id)
              (catch Throwable e
                (log/info "Error in import-pmids-to-project-with-meta!"
                          (.getMessage e))
                false))]
        (with-transaction
          ;; update source metadata
          (if success?
            (sources/update-project-source-metadata!
             source-id (assoc meta :importing-articles? false))
            (sources/fail-project-source-import! source-id))
          ;; update the enabled flag for the articles
          (sources/update-project-articles-enabled! project-id))
        ;; start threads for updates from api.insilica.co
        (when success?
          (predict-api/schedule-predict-update project-id)
          (importance/schedule-important-terms-update project-id))
        success?))))

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

(def oa-root-link "https://www.ncbi.nlm.nih.gov/pmc/utils/oa/oa.fcgi")

(defn pdf-ftp-link
  "Given a pmicd (PMC*), return the ftp link for the pdf, if it exists, nil otherwise"
  [pmcid]
  (when pmcid
    (util/wrap-retry
     (fn []
       (let [parsed-html (-> (http/get oa-root-link
                                       {:query-params {"id" pmcid}})
                             :body
                             hickory/parse
                             hickory/as-hickory)]
         (-> (s/select (s/child (s/attr :format #(= % "pdf")))
                       parsed-html)
             first
             (get-in [:attrs :href]))))
     :fname "pdf-ftp-link" :max-retries 5 :retry-delay 1000)))

(defn article-pmcid-pdf-filename
  "Given a pmcid (PMC*), return the filename of the pdf returned for that pmcid, if it exists, nil otherwise"
  [pmcid]
  (when-let [pdf-link (pdf-ftp-link pmcid)]
    (let [filename (fs/base-name pdf-link)
          client (-> pdf-link
                     (clojure.string/replace #"ftp://" "ftp://anonymous:pwd@")
                     (clojure.string/replace (re-pattern filename) ""))]
      (cond
        ;; the file already exists, return the filename
        (fs/exists? filename)
        filename
        ;; retrieve the file
        (ftp/with-ftp [client client]
          (ftp/client-get client filename filename))
        filename
        :else nil))))

;;(ftp/with-ftp [client "ftp://anonymous:pwd@ftp.ncbi.nlm.nih.gov/pub/pmc/oa_pdf/92/86"] (ftp/client-get client "dddt-12-721.PMC5892952.pdf"))
;;
;; when I use {:headers {"User-Agent" "Apache-HttpClient/4.5.5"}}
;; I get a "Forbidden" with a message that contains the link
;; https://www.ncbi.nlm.nih.gov/pmc/about/copyright/
;;
;; I can still retrieve the data
;; when I use {:headers {"User-Agent" "curl/7.54.0"}}
;; as well as {:headers {"User-Agent" "clj-http"}}
;;
;; (http/get "https://www.ncbi.nlm.nih.gov/pmc/utils/oa/oa.fcgi?id=PMC5892952")
;; need to: parse the html
;;          retrieve the file with ftp

;;;
;;; Cassandra/PubMed test queries
;;;

;;; Compare raw article XML (PubMed, Cassandra)
#_ (-> (fetch-pmids-xml [22152580]))
#_ (-> (cdb/get-pmids-xml [22152580]) first)

;;; Compare fetch of ~200 entries (PubMed, Cassandra)
#_ (-> (fetch-pmid-entries (range 22152580 22152780)) count time)
#_ (-> (fetch-pmid-entries-insilica (range 22152580 22152780)) count time)

;;; Compare size of total text data
;;; Note: size difference due in part to :raw values (whitespace, content)
#_ (-> (fetch-pmid-entries (range 22152580 22152780)) pr-str count time)
#_ (-> (fetch-pmid-entries-insilica (range 22152580 22152780)) pr-str count time)

;;; Compare parsed articles
#_ (-> (fetch-pmid-entries [22152580]) first (dissoc :raw))
#_ (-> (fetch-pmid-entries-insilica [22152580]) first (dissoc :raw))

;;; Compare abstract values
#_ (= (-> (fetch-pmid-entries [22152580]) first :abstract)
      (-> (fetch-pmid-entries-insilica [22152580]) first :abstract))

;;; NOTE:
;;;
;;; PubMed API returns <PubmedArticle> element containing both
;;; <MedlineCitation> and <PubmedData>
;;;
;;; Cassandra XML includes only <MedlineCitation>, leaving out some dates
;;; and references to external article IDs ("pubmed", "pii", "doi")
;;; which are present in <PubmedData>
