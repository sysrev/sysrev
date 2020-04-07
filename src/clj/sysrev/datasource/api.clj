(ns sysrev.datasource.api
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [orchestra.core :refer [defn-spec]]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.query-types :as qt]
            [sysrev.util :as util :refer
             [assert-pred map-keys parse-integer apply-keyargs req-un opt-keys
              gquery url-join index-by]])
  (:import [com.fasterxml.jackson.core JsonParseException JsonProcessingException]))

;; for clj-kondo
(declare fetch-pubmed-articles fetch-nct-entities get-articles-content fetch-ris-articles-by-ids)

(defonce ds-host-override (atom nil))
(defonce ds-auth-key-override (atom nil))

(defn ds-auth-key []
  (or @ds-auth-key-override (env :datasource-auth-key)))

(defn ds-host []
  (or @ds-host-override (env :datasource-host) "https://datasource.insilica.co"))

(def ^:private academic-fields
  [:primary-title :secondary-title :date :urls :document-ids :abstract :keywords
   :year :notes :remote-database-name :authors :work-type])

(defn- field-to-graphql [field]
  (some-> field db/clj-identifier-to-sql keyword))

(defn- field-from-graphql [field]
  (some-> field db/sql-identifier-to-clj keyword))

(defn- all-pubmed-fields []
  (let [unused #{:urls :document-ids :notes :remote-database-name :work-type}]
    (remove #(contains? unused %) academic-fields)))

(defn- auth-header [& {:keys [auth-key]
                       :or {auth-key (ds-auth-key)}}]
  (let [auth-key (->> auth-key (assert-pred string?) (assert-pred not-empty))]
    {"Authorization" (str "Bearer " auth-key)}))

(defn run-ds-query [query & {:keys [host auth-key]}]
  (http/post (str (or host (ds-host)) "/graphql")
             {:headers (if (seq auth-key)
                         (auth-header :auth-key auth-key)
                         (auth-header))
              :body (json/write-str {:query query})
              :content-type :application/json
              :as :json, :coerce :always, :throw-exceptions false}))

(defn-spec ^:private parse-ds-response (s/every map?)
  "Extracts sequence of results from GraphQL response, converting
   field keywords back from graphql-format to clojure-format."
  [{:keys [status body] :as response} map?, extract-result ifn?]
  (when-not (= status 200)
    (throw (ex-info (str "datasource responded with status " status)
                    {:type ::response-status})))
  (->> (try (extract-result body)
            (catch Throwable e
              (throw (ex-info (str "exception in extract-result: " (.getMessage e))
                              {:type ::response-parse} e))))
       (mapv #(map-keys field-from-graphql %))))

(s/def ::pmids (s/nilable (s/every #(int? (parse-integer %)))))
(s/def ::nctids (s/nilable (s/every string?)))

(s/def ::name keyword?)
(s/def ::args (s/map-of keyword? any?))
(s/def ::fields (s/coll-of keyword?))

(declare query-api)

(defn-spec query-api (s/nilable sequential?)
  "Runs a GraphQL query against the datasource API and returns a
   sequence of the results."
  [{:keys [name args fields]} (req-un ::name ::args ::fields)]
  (-> (gquery [[name args (mapv field-to-graphql fields)]])
      (run-ds-query)
      (parse-ds-response #(get-in % [:data name]))))

(defn-spec fetch-pubmed-articles (s/map-of int? map?)
  "Queries datasource API to return article data for sequence `pmids`,
   returning a map of pmid -> article."
  [pmids ::pmids, & {:keys [fields]} (opt-keys ::fields) ]
  (let [pmids (mapv parse-integer pmids)]
    #_ (log/infof "fetching %d pubmed articles" (count pmids))
    (->> (query-api {:name :pubmedEntities
                     :args {:pmids pmids}
                     :fields (concat [:id :pmid] (or fields (all-pubmed-fields)))})
         (index-by :pmid))))

(defn fetch-ris-articles-by-hash
  "Queries datasource API to return article data for sequence `pmids`,
   returning a map of pmid -> article."
  [hash]
  (->> (gquery [[:risFileCitationsByFileHash {:hash hash}
                 [:TI :T1 :BT :CT :id]]])
       (run-ds-query)
       :body :data :risFileCitationsByFileHash))

(defn fetch-pubmed-article
  "Queries datasource API to return article data map for a single `pmid`."
  [pmid & {:as opts}]
  (let [pmid (parse-integer pmid)]
    (-> (apply-keyargs fetch-pubmed-articles [pmid] opts)
        (get pmid))))

;; TODO: support this for article import (analogous to `fetch-pubmed-articles`)
(defn-spec fetch-nct-entities (s/map-of string? map?)
  "Queries datasource API to return article data for sequence `nctids`,
   returning a map of nctid -> article."
  [nctids ::nctids, & {:keys [fields]} (opt-keys ::fields)]
  (->> (query-api {:name :clinicalTrialEntities
                   :args {:nctids nctids}
                   :fields (concat [:nctid] (or fields [:json]))})
       (map (fn [entry] (update entry :json #(json/read-str % :key-fn keyword))))
       (index-by :nctid)))

(defn fetch-nct-entry
  "Queries datasource to return article data map for a single `nctid`."
  [nctid & {:as opts}]
  (-> (apply-keyargs fetch-nct-entities [nctid] opts)
      (get nctid)))

(defn fetch-entities
  "Queries datasource to return map of entity-id -> entity for `entity-ids`."
  [entity-ids]
  (->> (query-api {:name :entities
                   :args {:ids entity-ids}
                   :fields [:content :mimetype :id]})
       (index-by :id)))

(defmulti enrich-articles (fn [datasource _articles] datasource))

(defmethod enrich-articles :default [_ articles]
  (vec (for [{:keys [content] :as x} articles]
         (merge content (select-keys x [:article-id :project-id])))))

(defmethod enrich-articles "pubmed" [_ articles]
  (let [data (->> articles (map :external-id) (fetch-pubmed-articles))]
    (vec (for [{:keys [external-id] :as x} articles]
           (merge (get data (parse-integer external-id))
                  (select-keys x [:article-id :project-id]))))))

(defn fetch-ris-articles-by-ids
  "Queries datasource API to get article data for sequence `ids`,
   returning a map of id -> article."
  [ids]
  (let [ids (mapv parse-integer ids)]
    (get-in (->> (gquery [[:risFileCitationsByIds {:ids ids}
                           [:TI :T1 :T2 :Y1 :PY :DA :AB :KW :JO :N2 :JF :JA :J1 :J2 :BT :CT
                            :AU :A1 :A2 :A3 :A4 :TA :id]]])
                 (run-ds-query))
            [:body :data :risFileCitationsByIds])))

(defn process-ris-content [content]
  (let [{:keys [TI T1 T2 Y1 PY DA AB KW JO N2 JF JA J1 J2 BT CT
                AU A1 A2 A3 A4 TA id]} content
        get-values #(->> % (sort-by count) last first)]
    {:primary-title    (get-values [TI T1 BT CT])
     :secondary-title  (get-values [T2 JO JF JA J1 J2])
     :date             (get-values [Y1 DA PY])
     :abstract         (get-values [N2 AB])
     :authors          (into [] (flatten (conj AU A1 A2 A3 A4 TA)))
     :id               (parse-integer id)
     :keywords         (str/join "," KW)}))

(defmethod enrich-articles "RIS" [_ articles]
  (let [data (->> (map :external-id articles)
                  fetch-ris-articles-by-ids
                  (map process-ris-content)
                  (index-by :id))]
    (vec (for [{:keys [external-id] :as x} articles]
           (merge (get data (parse-integer external-id))
                  (select-keys x [:article-id :project-id]))))))

(defmethod enrich-articles "ctgov" [_ articles]
  (let [data (->> articles (map :external-id) fetch-nct-entities)]
    (vec (for [{:keys [external-id] :as x} articles]
           (merge (get data external-id)
                  (select-keys x [:article-id :project-id :datasource-name :primary-title]))))))

(defmethod enrich-articles "entity" [_ articles]
  (let [data (->> articles (map :external-id) fetch-entities)]
    (vec (for [{:keys [external-id] :as x} articles]
           (merge (select-keys (get data external-id) [:mimetype :content])
                  (select-keys x [:article-id :project-id :datasource-name :external-id]))))))

(defn enrich-articles-with-datasource
  "Given a coll of articles with `:datasource-name` and `:external-id`
  keys, enrich with content from datasource.  For every
  `:datasource-name` value, an `enrich-articles` method should be
  written. The `enrich-articles` method takes a coll of articles and
  enriches them with additional data."
  [articles]
  (->> (group-by :datasource-name articles)
       (map (fn [[k v]] (some->> v (enrich-articles k))))
       flatten))

(defn-spec get-articles-content (s/map-of int? map?)
  "Returns map of {id content} for `article-ids`, automatically taking
   content for each article from either datasource API or local
   `article-data` table."
  [article-ids (s/every int?)]
  (->> (qt/find-article {:article-id (distinct article-ids)}
                        [:a.* :ad.datasource-name :ad.external-id :ad.content]
                        :include-disabled true)
       enrich-articles-with-datasource
       (index-by :article-id)))

(defn get-article-content
  "Returns article content map for `article-id`."
  [article-id]
  (-> (get-articles-content [article-id])
      (get article-id)))

(defn-spec search-text-by-pmid (s/every int?)
  [search-query string?, source-pmids ::pmids]
  (letfn [(do-search [pmids]
            (->> (query-api {:name :searchPubmedEntities
                             :args {:pmids pmids :q search-query}
                             :fields [:id :pmid]})
                 (map #(-> % :pmid parse-integer))))]
    (->> (partition-all 1000 source-pmids)
         (pmap do-search)
         (apply concat))))

(defn create-ris-file
  "Given a file and filename, create a RIS file citation on datasource."
  [{:keys [file filename]}]
  (try (http/post (str (ds-host) "/files/ris")
                  {:headers (auth-header)
                   :multipart [{:name "filename" :content filename}
                               {:name "file" :content file}]
                   :as :json, :coerce :always, :throw-exceptions false})
       (catch JsonParseException _
         {:status 500 :body {:error "JsonParseException"}})
       (catch JsonProcessingException _
         {:status 500 :body {:error "JsonProcessingException"}})))

(defn download-file
  "Given a filename and hash, download a file from datasource"
  [{:keys [filename hash]}]
  (:body (http/get (url-join (ds-host) "entity" hash filename)
                   {:as :stream, :headers (auth-header)})))
