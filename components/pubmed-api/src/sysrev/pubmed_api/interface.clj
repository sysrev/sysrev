(ns sysrev.pubmed-api.interface
  (:require [clojure.data.xml :as dxml]
            [clojure.string :as str]
            [hato.client :as hc]
            [sysrev.memcached.interface :as mem]
            [sysrev.util-lite.interface :as ul]))

(defn cache-key [prefix url hato-opts]
  (->> hato-opts
       :query-params
       (sort-by key)
       pr-str
       ul/sha256-base64
       (str prefix url)))

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

(defn get-search-results-page
  [{:keys [api-key hato memcached ttl-sec]} query & {:keys [retmax retstart]}]
  (let [url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
        hato-opts {:http-client hato
                   :query-params
                   {:db "pubmed"
                    :key api-key
                    :retmax (or retmax 100)
                    :retstart (or retstart 0)
                    :term query}}
        body (mem/cache
              memcached
              (cache-key "pubmed-api/get-search-results/" url hato-opts)
              ttl-sec
              (:body (api-get url hato-opts)))
        docs (-> body dxml/parse-str :content)
        error-list (tag-val :ErrorList docs)]
    (cond
      (-> docs first :tag (= :ERROR))
      (throw (ex-info "PubMed API error" {:error docs}))

      (tag-val :PhraseNotFound (:content error-list))
      {:count 0}

      error-list
      (throw (ex-info "PubMed API query error" {:error-list error-list}))

      :else
      (let [ct (-> (tag-val :Count docs) :content first parse-long)
            ret-start (-> (tag-val :RetStart docs) :content first parse-long)
            id-list (-> (tag-val :IdList docs) :content)
            next-start (+ ret-start (count id-list))]
        {:count ct
         :next-start next-start
         :pmids (mapcat :content id-list)
         :ret-start ret-start
         #_(lazy-cat
            (mapcat :content id-list)
          ;; e-utils doesn't accept starts after 9998
            (when (and (seq docs) (> ct next-start) (<= 9998 next-start))
              (:pmids (get-search-results opts query next-start))))}))))

(defn get-fetches [{:keys [api-key hato memcached ttl-sec]} pmids]
  (let [url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi"
        hato-opts {:http-client hato
                   :query-params
                   {:db "pubmed"
                    :id (str/join "," pmids)
                    :key api-key
                    :retmode "text"
                    :rettype "xml"}
                   :timeout 10000}
        body (mem/cache
              memcached
              (cache-key "pubmed-api/get-fetches" url hato-opts)
              ttl-sec
              (:body
               (api-get url hato-opts)))]
    (-> body
        dxml/parse-str
        :content
        (->> (filter #(= :PubmedArticle (:tag %)))))))
