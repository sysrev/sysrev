(ns sysrev.import.pubmed
  (:require [clojure.set :as set]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.xml :as dxml]
            [clojure-csv.core :as csv]
            [clj-http.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [me.raynes.fs :as fs]
            [miner.ftp :as ftp]
            [sysrev.db.core :refer
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
            [sysrev.shared.util :as sutil
             :refer [in? map-values parse-integer]]))

(def use-cassandra-pubmed? true)

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

(defn extract-wrapped-text [x]
  (cond (string? x) x

        (and (map? x) (contains? x :tag) (contains? x :content)
             (-> x :content first string?))
        (-> x :content first)

        :else nil))

(defn parse-pmid-xml
  [pxml & {:keys [create-raw?] :or {create-raw? true}}]
  (try
    (let [title (->> [:MedlineCitation :Article :ArticleTitle]
                     (xml-find-value pxml))
          journal (->> [:MedlineCitation :Article :Journal :Title]
                       (xml-find-value pxml))
          abstract (->> [:MedlineCitation :Article :Abstract :AbstractText]
                        (xml-find pxml) parse-abstract)
          authors (->> [:MedlineCitation :Article :AuthorList :Author]
                       (xml-find pxml) parse-pubmed-author-names)
          pmid (->> [:MedlineCitation :PMID] (xml-find-value pxml))
          keywords (->> [:MedlineCitation :KeywordList :Keyword]
                        (xml-find-vector pxml)
                        (mapv extract-wrapped-text)
                        (filterv identity))
          locations (extract-article-location-entries pxml)
          year (or (->> [:MedlineCitation :DateCompleted :Year]
                        (xml-find [pxml])
                        first :content first parse-integer)
                   (->> [:MedlineCitation :Article :Journal :JournalIssue :PubDate :Year]
                        (xml-find [pxml])
                        first :content first parse-integer))
          month (or (->> [:MedlineCitation :DateCompleted :Month]
                         (xml-find [pxml])
                         first :content first parse-integer)
                    (->> [:MedlineCitation :Article :Journal :JournalIssue :PubDate :Month]
                         (xml-find [pxml])
                         first :content first))
          day (or (->> [:MedlineCitation :DateCompleted :Day]
                       (xml-find [pxml])
                       first :content first parse-integer)
                  (->> [:MedlineCitation :Article :Journal :JournalIssue :PubDate :Day]
                       (xml-find [pxml])
                       first :content first))]
      (cond->
          {:remote-database-name "MEDLINE"
           :primary-title title
           :secondary-title journal
           :abstract abstract
           :authors (mapv str/trim authors)
           :year year
           :keywords keywords
           :public-id (some-> pmid str)
           :locations locations
           :date (cond-> (str year)
                   month           (str "-" (if (and (integer? month)
                                                     (<= 1 month 9))
                                              "0" "")
                                        month)
                   (and month day) (str "-" (if (and (integer? day)
                                                     (<= 1 day 9))
                                              "0" "")
                                        day))}
          create-raw?
          (merge {:raw (dxml/emit-str pxml)})))
    (catch Throwable e
      (log/warn "parse-pmid-xml:"
                "error while parsing article -"
                (.getMessage e))
      (try
        (log/warn "xml =" (dxml/emit-str pxml))
        (catch Throwable e1 nil))
      nil)))

(defn fetch-pmids-xml [pmids]
  (util/wrap-retry
   (fn []
     (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi"
                   {:query-params {"db" "pubmed"
                                   "id" (str/join "," pmids)
                                   "retmode" "xml"
                                   "api_key" e-util-api-key}})
         :body))
   :fname "fetch-pmids-xml" :throttle-delay 100))

(defn fetch-pmid-entries [pmids]
  (->> (fetch-pmids-xml pmids)
       parse-xml-str :content
       (mapv parse-pmid-xml)))

(defn fetch-pmid-entries-cassandra [pmids]
  (let [result
        (when @cdb/active-session
          (try (->> (cdb/get-pmids-xml pmids)
                    (pmap #(some-> % parse-xml-str
                                   (parse-pmid-xml :create-raw? false)
                                   (merge {:raw %})))
                    vec)
               (catch Throwable e
                 (log/warn "fetch-pmid-entries-cassandra:"
                           "error while fetching or parsing")
                 nil)))]
    (if (empty? result)
      (fetch-pmid-entries pmids)
      (if (< (count result) (count pmids))
        (let [result-pmids
              (->> result (map #(some-> % :public-id parse-integer)))
              diff (set/difference (set pmids) (set result-pmids))
              have (set/difference (set pmids) (set diff))
              from-pubmed (fetch-pmid-entries (vec diff))]
          #_ (log/info "missing" (- (count pmids) (count result))
                       "PMID articles"
                       (str "(got " (count result) " of "
                            (count pmids) ")"))
          #_ (log/info "Missing:" (vec diff))
          #_ (log/info "Have:" (->> have (take 25) vec))
          #_ (log/info "retried from PubMed, got" (count from-pubmed) "articles")
          (concat result from-pubmed))
        result))))

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

(defn- import-pmids-to-project
  "Imports into project all articles referenced in list of PubMed IDs.
  Note that this will not import an article if the PMID already exists
  in the project."
  [pmids project-id source-id]
  (try
    (doseq [pmids-group (->> pmids sort
                             (partition-all (if use-cassandra-pubmed? 300 40)))]
      (let [group-articles (->> pmids-group
                                (#(if use-cassandra-pubmed?
                                    (fetch-pmid-entries-cassandra %)
                                    (fetch-pmid-entries %)))
                                (remove nil?))]
        (doseq [articles (->> group-articles (partition-all 10))]
          (let [public-ids (->> articles
                                (map :public-id)
                                (remove nil?)
                                (mapv str))]
            (try
              (with-transaction
                (let [existing-articles
                      (if (empty? public-ids)
                        []
                        (-> (select :article-id :public-id)
                            (from :article)
                            (where [:and
                                    [:= :project-id project-id]
                                    [:in :public-id public-ids]])
                            (->> do-query vec)))
                      existing-article-ids
                      (->> existing-articles (mapv :article-id))
                      existing-public-ids
                      (->> existing-articles (mapv :public-id) (filterv not-empty))
                      new-articles
                      (->> articles
                           (filter #(not-empty (:primary-title %)))
                           (filter :public-id)
                           (remove #(in? existing-public-ids (:public-id %))))
                      new-article-ids
                      (->> (map (fn [id article] {id article})
                                (articles/add-articles
                                 (->> new-articles
                                      (mapv #(-> %
                                                 (dissoc :locations)
                                                 (assoc :enabled false))))
                                 project-id *conn*)
                                new-articles)
                           (apply merge))
                      new-locations
                      (->> (keys new-article-ids)
                           (map (fn [article-id]
                                  (let [article (get new-article-ids article-id)]
                                    (->> (:locations article)
                                         (mapv #(assoc % :article-id article-id))))))
                           (apply concat)
                           vec)]
                  (sources/add-articles-to-source
                   (concat existing-article-ids (keys new-article-ids))
                   source-id)
                  (when (not-empty new-locations)
                    (-> (sqlh/insert-into :article-location)
                        (values new-locations)
                        do-execute))))
              (catch Throwable e
                (log/info "error importing pmids group:" (.getMessage e))
                (throw e)))))))
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
  (let [source-id (sources/create-source
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
              (sources/update-source-meta
               source-id (assoc meta :importing-articles? false))
              (sources/fail-source-import source-id))
            ;; update the enabled flag for the articles
            (sources/update-project-articles-enabled project-id))
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
            (sources/update-source-meta
             source-id (assoc meta :importing-articles? false))
            (sources/fail-source-import source-id))
          ;; update the enabled flag for the articles
          (sources/update-project-articles-enabled project-id))
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
      ;; retrieve file if not already present
      (when-not (fs/exists? filename)
        (ftp/with-ftp [client client]
          (ftp/client-get client filename filename)))
      ;; return filename if file exists
      (when (fs/exists? filename)
        filename))))

(defn compare-fetched-pmids
  "Returns list of differences between PubMed entries fetched from PubMed API
  and Cassandra for a range of PMID values."
  [start count & [offset]]
  (let [pmids (range (+ start offset) (+ start offset count))
        direct (->> (fetch-pmid-entries pmids) (map #(dissoc % :raw)))
        cdb (->> (fetch-pmid-entries-cassandra pmids) (map #(dissoc % :raw)))]
    (->> (mapv (fn [d c]
                 (when (not= d c)
                   (->> [(keys d) (keys c)]
                        (apply concat)
                        distinct
                        (mapv (fn [k]
                                (when (not= (get d k) (get c k))
                                  {k {:direct (get d k)
                                      :cassandra (get c k)}})))
                        (remove nil?)
                        (apply merge))))
               direct cdb)
         (remove nil?)
         vec)))

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
#_ (-> (fetch-pmid-entries-cassandra (range 22152580 22152780)) count time)

;;; Compare size of total text data
;;; Note: size difference due in part to :raw values (whitespace, content)
#_ (-> (fetch-pmid-entries (range 22152580 22152780)) pr-str count time)
#_ (-> (fetch-pmid-entries-cassandra (range 22152580 22152780)) pr-str count time)

;;; Compare parsed articles
#_ (-> (fetch-pmid-entries [22152580]) first (dissoc :raw) pr-str)
#_ (-> (fetch-pmid-entries-cassandra [22152580]) first (dissoc :raw) pr-str)

;;; Compare abstract values
#_ (= (-> (fetch-pmid-entries [22152580]) first :abstract)
      (-> (fetch-pmid-entries-cassandra [22152580]) first :abstract))

;;; NOTE:
;;;
;;; PubMed API returns <PubmedArticle> element containing both
;;; <MedlineCitation> and <PubmedData>
;;;
;;; Cassandra XML includes only <MedlineCitation>, leaving out some dates
;;; and references to external article IDs ("pubmed", "pii", "doi")
;;; which are present in <PubmedData>
