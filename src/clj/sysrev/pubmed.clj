(ns sysrev.pubmed
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.data.xml :as dxml]
            [clj-http.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as hs]
            [me.raynes.fs :as fs]
            [miner.ftp :as ftp]
            [sysrev.config.core :as config]
            [sysrev.cassandra :as cdb]
            [sysrev.util :as util :refer
             [parse-xml-str xml-find xml-find-value xml-find-vector]]
            [sysrev.util :as u]
            [sysrev.shared.util :as sutil
             :refer [in? map-values parse-integer]]))

(def use-cassandra-pubmed? true)

(def e-util-api-key (:e-util-api-key config/env))

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
        (str/join "\n\n" paragraphs)))
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
      (log/warn "parse-pmid-xml:" "error while parsing article -" (.getMessage e))
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
                                   "id" (str/join "," pmids)
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
        retmax (:max-import-articles config/env)
        max-pages (int (Math/ceil (/ total-pmids retmax)))]
    (->> (range 0 max-pages)
         (mapv
          (fn [page]
            (mapv #(Integer/parseInt %)
                  (get-in (get-search-query query retmax (* page retmax))
                          [:esearchresult :idlist]))))
         (apply concat)
         vec)))

(defn compare-fetched-pmids
  "Test function for Cassandra/PubMed import.

  Returns list of differences between PubMed entries fetched from
  PubMed API and Cassandra for a range of PMID values."
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

(def oa-root-link "https://www.ncbi.nlm.nih.gov/pmc/utils/oa/oa.fcgi")

(defn pdf-ftp-link
  "Given a pmicd (PMC*), return the ftp link for the pdf, if it exists, nil otherwise"
  [pmcid]
  (when pmcid
    (u/wrap-retry
     (fn []
       (let [parsed-html (-> (http/get oa-root-link
                                       {:query-params {"id" pmcid}})
                             :body
                             hickory/parse
                             hickory/as-hickory)]
         (-> (hs/select (hs/child (hs/attr :format #(= % "pdf")))
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
                     (str/replace #"ftp://" "ftp://anonymous:pwd@")
                     (str/replace (re-pattern filename) ""))]
      ;; retrieve file if not already present
      (when-not (fs/exists? filename)
        (ftp/with-ftp [client client]
          (ftp/client-get client filename filename)))
      ;; return filename if file exists
      (when (fs/exists? filename)
        filename))))

;;(ftp/with-ftp [client "ftp://anonymous:pwd@ftp.ncbi.nlm.nih.gov/pub/pmc/oa_pdf/92/86"] (ftp/client-get client "dddt-12-721.PMC5892952.pdf"))
;;
;; when I use {:headers {"User-Agent" "Apache-HttpClient/4.5.5"}}
;; I get a "Forbidden" with a message that contains the link
;; https://www.ncbi.nlm.nih.gov/pmc/about/copyright/
;;
;; I can still retrieve the data
;; when I use {:headers {"User-Agent" "curl/7.54.0"}}
;; as well as {:headers {"User-Agent" "clj-http"}}
