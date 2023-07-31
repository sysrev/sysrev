(ns sysrev.formats.pubmed
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [hickory.core :as hickory]
            [hickory.select :as hs]
            [me.raynes.fs :as fs]
            [miner.ftp :as ftp]
            [sysrev.config :as config]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.util-lite.interface :as ul]))

(def e-util-api-key (:e-util-api-key config/env))

;; https://www.ncbi.nlm.nih.gov/books/NBK25500/#chapter1.Searching_a_Database
(defn get-search-query
  "Given a query and retstart value, fetch the json associated with that
  query. Return a EDN map of that data. A page size is 20 PMIDs and
  starts on page 1."
  [query retmax retstart]
  (ul/retry
   {:interval-ms 1000
    :n 3
    :throw-pred #(-> % ex-data :query)}
   (try
     (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                   {:query-params {"db" "pubmed"
                                   "term" query
                                   "retmode" "json"
                                   "retmax" retmax
                                   "retstart" retstart
                                   "api_key" e-util-api-key}})
         :body
         util/read-json)
     (catch clojure.lang.ExceptionInfo e
       (if (-> e ex-data :reason-phrase (= "Request-URI Too Long"))
         (throw (ex-info "Query too long" {:query query} e))
         (throw e))))))

(defn get-search-query-response
  "Given a query and page number, return a EDN map corresponding to a
  JSON response. A page size is 20 PMIDs and starts on page 1."
  [query page-number]
  (try
    (let [retmax 20
          esearch-result (:esearchresult (get-search-query query retmax (* (- page-number 1) retmax)))]
      (if (:ERROR esearch-result)
        {:pmids [] :count 0}
        {:pmids (mapv parse-integer (:idlist esearch-result))
         :count (parse-integer (:count esearch-result))}))
    (catch clojure.lang.ExceptionInfo e
      (if (-> e ex-message (= "Query too long"))
        {:error {:message "Query too long"}}
        (throw e)))))

(defn get-pmids-summary
  "Given a vector of PMIDs, return the summaries as a map"
  [pmids]
  (ul/retry
   {:interval-ms 1000
    :n 3}
   (-> (http/get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                 {:query-params {"db" "pubmed"
                                 "retmode" "json"
                                 "id" (str/join "," pmids)
                                 "api_key" e-util-api-key}})
       :body
       (json/read-str :key-fn #(or (parse-integer %) (keyword %)))
       :result)))

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
    (ul/retry
     {:interval-ms 1000
      :n 3}
     (let [parsed-html (-> (http/get oa-root-link {:query-params {"id" pmcid}})
                           :body
                           hickory/parse
                           hickory/as-hickory)]
       (-> (hs/select (hs/child (hs/attr :format #(= % "pdf")))
                      parsed-html)
           first
           (get-in [:attrs :href]))))))

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
