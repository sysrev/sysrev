(ns sysrev.datasource.api
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [venia.core :as venia]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.db.query-types :as qt]
            [sysrev.datasource.core :as ds]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer
             [assert-pred map-keys parse-integer apply-keyargs req-un opt-keys]]))

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

(defn- auth-header []
  (let [auth-key (->> (ds-auth-key) (assert-pred string?) (assert-pred not-empty))]
    {"Authorization" (str "Bearer " auth-key)}))

(defn- run-ds-query [query & {:keys [host]}]
  (http/post (str (or host (ds-host)) "/graphql")
             {:headers (auth-header)
              :body (json/write-str {:query query})
              :as :json}))

(defn-spec ^:private parse-ds-response (s/every map?)
  "Extracts sequence of results from GraphQL response, converting
   field keywords back from graphql-format to clojure-format."
  [{:keys [status body] :as response} map?, extract-result ifn?]
  (when-not (= status 200)
    (throw (ex-info (str "datasource responded with status " status)
                    {:type ::response-status})))
  (->> (try (extract-result body)
            (catch Throwable cause
              (throw (ex-info (str "exception in extract-result: " (.getMessage cause))
                              {:type ::response-parse} cause))))
       (mapv #(map-keys field-from-graphql %))))

(s/def ::pmids (s/nilable (s/every #(int? (parse-integer %)))))
(s/def ::nctids (s/nilable (s/every string?)))

(s/def ::name keyword?)
(s/def ::args (s/map-of keyword? any?))
(s/def ::fields (s/coll-of keyword?))

(defn-spec query-api (s/nilable sequential?)
  "Runs a GraphQL query against the datasource API and returns a
   sequence of the results."
  [{:keys [name args fields]} (req-un ::name ::args ::fields)]
  (-> (venia/graphql-query
       {:venia/queries [[name args (mapv field-to-graphql fields)]]})
      (run-ds-query)
      (parse-ds-response #(get-in % [:data name]))))

(defn-spec fetch-pubmed-articles (s/map-of int? map?)
  "Queries datasource API to get article data for sequence `pmids`,
   returning a map of {pmid article}."
  [pmids ::pmids, & {:keys [fields]} (opt-keys ::fields) ]
  (let [pmids (mapv parse-integer pmids)]
    #_ (log/infof "fetching %d pubmed articles" (count pmids))
    (->> (query-api {:name :pubmedEntities
                     :args {:pmids pmids}
                     :fields (concat [:id :pmid] (or fields (all-pubmed-fields)))})
         (sutil/index-by :pmid))))

(defn fetch-pubmed-article
  "Queries datasource API to get article data map for a single `pmid`."
  [pmid & {:as opts}]
  (let [pmid (parse-integer pmid)]
    (-> (apply-keyargs fetch-pubmed-articles [pmid] opts)
        (get pmid))))

;; TODO: support this for article import (analogous to `fetch-pubmed-articles`)
(defn-spec fetch-nct-entries (s/map-of string? map?)
  "Queries datasource API to get article data for sequence `nctids`,
   returning a map of {nctid article}."
  [nctids ::nctids, & {:keys [fields]} (opt-keys ::fields)]
  (->> (query-api {:name :clinicalTrialEntities
                   :args {:nctids nctids}
                   :fields (concat [:id :nctid] (or fields [:json]))})
       (map (fn [entry] (update entry :json #(json/read-str % :key-fn keyword))))
       (sutil/index-by :nctid)))

(defn fetch-nct-entry
  "Queries datasource API to get article data map for a single `nctid`."
  [nctid & {:as opts}]
  (-> (apply-keyargs fetch-nct-entries [nctid] opts)
      (get nctid)))

(defn-spec get-articles-content (s/map-of int? map?)
  "Returns map of {id content} for `article-ids`, automatically taking
   content for each article from either datasource API or local
   `article-data` table."
  [article-ids (s/every int?)]
  (let [articles (qt/find-article {:article-id article-ids}
                                  [:a.* :datasource-name :external-id :content]
                                  :include-disabled true)
        pmids (sort (->> articles
                         (map #(when (ds/pubmed-data? %)
                                 (parse-integer (:external-id %))))
                         (remove nil?)))
        data (some-> (seq pmids) fetch-pubmed-articles)]
    (->> articles
         (mapv #(merge (or (get data (-> % :external-id parse-integer))
                           (:content %))
                       (select-keys % [:article-id :project-id])))
         (sutil/index-by :article-id))))

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
                 (map :pmid)
                 (map parse-integer)))]
    (->> (partition-all 1000 source-pmids)
         (pmap do-search)
         (apply concat))))
