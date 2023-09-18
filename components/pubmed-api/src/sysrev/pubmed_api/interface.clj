(ns sysrev.pubmed-api.interface
  (:require [clojure.data.xml :as dxml]
            [clojure.string :as str]
            [hato.client :as hc]
            [sysrev.memcached.interface :as mem]))

(def default-ttl-sec 3600)

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
  [{:as opts
    :keys [api-key hato memcached ttl-sec]
    :or {ttl-sec default-ttl-sec}}
   pmids]
  (let [[cached-articles-raw uncached-pmids] (split-cached-fetches opts pmids)
        cached-articles (map dxml/parse-str cached-articles-raw)]
    (concat
     cached-articles
     (fetch-uncached-articles opts uncached-pmids))))
