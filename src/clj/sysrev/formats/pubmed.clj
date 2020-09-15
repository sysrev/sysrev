(ns sysrev.formats.pubmed
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
            [sysrev.config :as config]
            [sysrev.cassandra :as cdb]
            [sysrev.util :as util :refer
             [parse-integer ensure-pred ignore-exceptions
              parse-xml-str xml-find xml-find-value xml-find-vector]]))

(def e-util-api-key (:e-util-api-key config/env))

(defn ^:unused extract-article-location-entries
  "Extracts entries for article_location from parsed PubMed API XML article."
  [pxml]
  (distinct
   (concat
    (->> (xml-find pxml [:MedlineCitation :Article :ELocationID])
         (map (fn [{_tag :tag, {source :EIdType} :attrs, content :content}]
                (map #(hash-map :source source, :external-id %) content)))
         (apply concat))
    (->> (xml-find pxml [:PubmedData :ArticleIdList :ArticleId])
         (map (fn [{_tag :tag, {source :IdType} :attrs, content :content}]
                (map #(hash-map :source source, :external-id %) content)))
         (apply concat)))))

;; https://www.ncbi.nlm.nih.gov/books/NBK25500/#chapter1.Searching_a_Database
(defn get-search-query
  "Given a query and retstart value, fetch the json associated with that
  query. Return a EDN map of that data. A page size is 20 PMIDs and
  starts on page 1."
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
  "Given a query and page number, return a EDN map corresponding to a
  JSON response. A page size is 20 PMIDs and starts on page 1."
  [query page-number]
  (let [retmax 20
        esearch-result (:esearchresult (get-search-query query retmax (* (- page-number 1) retmax)))]
    (if (:ERROR esearch-result)
      {:pmids [] :count 0}
      {:pmids (mapv parse-integer (:idlist esearch-result))
       :count (parse-integer (:count esearch-result))})))

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
         (json/read-str :key-fn #(or (parse-integer %) (keyword %)))
         :result))
   :fname "get-pmids-summary"))

(defn get-all-pmids-for-query
  "Given a search query, return all PMIDs as a vector of integers"
  [query]
  (let [total-pmids (:count (get-search-query-response query 1))
        retmax (:max-import-articles config/env)
        max-pages (int (Math/ceil (/ total-pmids retmax)))]
    (->> (for [page (range 0 max-pages)]
           (->> (get-in (get-search-query query retmax (* page retmax))
                        [:esearchresult :idlist])
                (map parse-integer)))
         (apply concat)
         vec)))

(def oa-root-link "https://www.ncbi.nlm.nih.gov/pmc/utils/oa/oa.fcgi")

(defn pdf-ftp-link
  "Returns an ftp url (if exists) for a Pubmed PMCID pdf reference."
  [pmcid]
  (when pmcid
    (util/wrap-retry
     (fn []
       (let [parsed-html (-> (http/get oa-root-link {:query-params {"id" pmcid}})
                             :body
                             hickory/parse
                             hickory/as-hickory)]
         (-> (hs/select (hs/child (hs/attr :format #(= % "pdf")))
                        parsed-html)
             first
             (get-in [:attrs :href]))))
     :fname "pdf-ftp-link" :retry-delay 1000)))

(defn article-pmcid-pdf-filename
  "Return filename of the pdf for a pmcid (\"PMC*\") if exists."
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
