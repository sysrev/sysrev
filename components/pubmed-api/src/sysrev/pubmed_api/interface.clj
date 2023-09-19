(ns sysrev.pubmed-api.interface
  (:require [clojure.data.xml :as dxml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as hc]
            [me.raynes.fs :as fs]
            [miner.ftp :as ftp]
            [sysrev.memcached.interface :as mem])
  (:import [java.io FileInputStream BufferedInputStream]
           [org.apache.commons.compress.compressors.gzip GzipCompressorInputStream]
           [org.apache.commons.compress.archivers.tar TarArchiveInputStream]))

(def default-ttl-sec 86400)

;; Rate limit is 10 requests per second per API key.
;; We try to limit requests to once per 100 ms to stay under on average.
;; If we still get a 429, hold off on requests for 1000 ms and retry once.
(defonce last-request (atom (System/nanoTime)))

(defn api-get [url {:keys [throw-exceptions?] :as opts}]
  (locking last-request
    (let [diff-ms (quot (- (System/nanoTime) @last-request) 1000000)]
      (when (> 100 diff-ms)
        (Thread/sleep diff-ms))
      (reset! last-request (System/nanoTime))))
  (let [{:keys [status] :as response} (hc/get url (assoc opts :throw-exceptions? false))]
    (cond
      (= 429 status)
      (do
        (locking last-request
          (Thread/sleep 1000)
          (reset! last-request (System/nanoTime)))
        (hc/get url opts))

      (<= 200 status 300)
      response

      throw-exceptions?
      (throw
       (ex-info
        (str "Unexpected status: " status)
        {:opts opts
         :response response
         :url url}))

      :else response)))

(defn tag-val [tag-name content]
  (some #(when (= tag-name (:tag %)) %) content))

(defn tag-content [tag-name content]
  (:content (tag-val tag-name content)))

(defn- split-cached-fetches [{:keys [memcached]} pmids]
  (let [pmids-and-articles (->> pmids
                                (map parse-long)
                                (map
                                 #(mem/cache-get
                                   memcached
                                   (str "sysrev.pubmed-api/article/" %)
                                   %)))]
    [(remove number? pmids-and-articles) (filter number? pmids-and-articles)]))

(defn- fetch-uncached-articles
  [{:as opts
    :keys [api-key hato memcached ttl-sec]
    :or {ttl-sec default-ttl-sec}}
   pmids]
  (when (seq pmids)
    (lazy-seq
     (let [chunk-size 20
           chunk (take chunk-size pmids)
           url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi"
           hato-opts {:http-client hato
                      :query-params
                      {:db "pubmed"
                       :id (str/join "," chunk)
                       :key api-key
                       :retmode "text"
                       :rettype "xml"}
                      :timeout 10000}
           articles (-> (api-get url hato-opts)
                        :body
                        ;; :supporting-external-entities is needed for "mathml-in-pubmed"
                        (dxml/parse-str {:supporting-external-entities true})
                        :content
                        (->> (filter #(= :PubmedArticle (:tag %)))))]
       (concat
        (map
         (fn [pmid article]
           (mem/cache-set
            memcached
            (str "sysrev.pubmed-api/article/" pmid)
            ttl-sec
            (dxml/emit-str article))
           article)
         chunk
         articles)
        (fetch-uncached-articles opts (drop chunk-size pmids)))))))

(defn get-fetches
  "Fetch the article XML from PubMed for pmids"
  [{:as opts
    :keys [api-key hato memcached ttl-sec]
    :or {ttl-sec default-ttl-sec}}
   pmids]
  (let [[cached-articles-raw uncached-pmids] (split-cached-fetches opts pmids)
        cached-articles (map dxml/parse-str cached-articles-raw)]
    (concat
     cached-articles
     (fetch-uncached-articles opts uncached-pmids))))

(defn article-abstract
  "Parse the abstract from an article's XML"
  [pubmed-article]
  (->> pubmed-article
       :content
       (tag-content :MedlineCitation)
       (tag-content :Article)
       (tag-content :Abstract)
       (tag-content :AbstractText)
       (mapcat #(if (string? %) [%] (:content %)))
       (str/join " ")))

(defn article-pmcid
  "Parse the PubMed Central ID from an article's XML"
  [pubmed-article]
  (->> pubmed-article
       :content
       (tag-content :PubmedData)
       (tag-content :ArticleIdList)
       (filter #(= "pmc" (:IdType (:attrs %))))
       first
       :content
       first))

(defn article-title
  "Parse the title from an article's XML"
  [pubmed-article]
  (->> pubmed-article
       :content
       (tag-content :MedlineCitation)
       (tag-content :Article)
       (tag-content :ArticleTitle)
       (str/join " ")))

(defn get-pmc-links
  "Get the Pubmed Central Open Access download links for a pmcid"
  [{:keys [hato memcached ttl-sec]
    :or {ttl-sec default-ttl-sec}}
   pmcid]
  (let [url "https://www.ncbi.nlm.nih.gov/pmc/utils/oa/oa.fcgi"
        hato-opts {:http-client hato
                   :query-params
                   {:id (str pmcid)}
                   :timeout 10000}
        body (mem/cache
              memcached
              (str "sysrev.pubmed-api/article/get-pmc-links/" pmcid)
              ttl-sec
              (:body (api-get url hato-opts)))
        content (-> body dxml/parse-str :content)
        error (->> content (tag-val :error))
        code (-> error :attrs :code)]
    (cond
      (= "idIsNotOpenAccess" code) nil
      error (throw (ex-info (str "Error from PubMed API for " pmcid ": " code)
                            {:code code :pmcid pmcid}))
      :else
      (->> body
           dxml/parse-str
           :content
           (tag-content :records)
           (tag-content :record)
           (map :attrs)))))

(defn download-pmc-file
  "Download a Pubmed Central Open Access file"
  [ftp-url file]
  (let [filename (fs/base-name ftp-url)
        url (-> ftp-url
                (str/replace #"ftp://" "ftp://anonymous:pwd@")
                (str/replace (re-pattern filename) ""))]
    (ftp/with-ftp [client url
                   :file-type :binary]
      (ftp/client-get client filename file))))

(defn get-pmc-file-nxml
  "Returns a parsed .nxml file when given a .tar.gz containing a .nxml file"
  [filename]
  (with-open [file-stream (FileInputStream. filename)
              buffered-in (BufferedInputStream. file-stream)
              gzip-in (GzipCompressorInputStream. buffered-in)
              tar-in (TarArchiveInputStream. gzip-in)]
    (loop []
      (let [entry (.getNextTarEntry tar-in)]
        (when entry
          (if (str/ends-with? (.getName entry) ".nxml")
            (-> tar-in io/reader slurp (dxml/parse-str {:support-dtd false}))
            (recur)))))))

(defn html->plaintext
  "Returns a seq of Strings represeting a plaintext version of html.
   The html should be in XML-style :tag :content :attr maps."
  [html]
  (cond
    (string? html) [html]
    (map? html)
    #__ (let [{:keys [content tag]} html]
          (cond
            (= :xref tag) nil
            (= :p tag) (concat
                        (mapcat html->plaintext content)
                        ["\n\n"])
            :else (mapcat html->plaintext content)))
    (seqable? html) (mapcat html->plaintext html)))

(defn nxml-text
  "Returns a String representing an plaintext version of an nxml
   article's full text."
  [nxml]
  (->> nxml
       :content
       (tag-val :body)
       html->plaintext
       (str/join "")))
