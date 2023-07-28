(ns sysrev.datasource.api
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [orchestra.core :refer [defn-spec]]
            [medley.core :as medley]
            [sysrev.config :refer [env]]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.ris.interface :as ris]
            [sysrev.util
             :as
             util
             :refer
             [assert-pred gquery index-by opt-keys
              parse-integer req-un url-join]]
            [venia.core :as venia]))

;; for clj-kondo
(declare fetch-pubmed-articles get-articles-content fetch-ris-articles-by-ids)

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
              :body (util/write-json {:query query})
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
       (mapv #(medley/map-keys field-from-graphql %))))

(s/def ::pmids (s/nilable (s/every #(int? (parse-integer %)))))

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
  [pmids ::pmids, & {:keys [fields]} (opt-keys ::fields)]
  (let [pmids (mapv parse-integer pmids)]
    #_(log/infof "fetching %d pubmed articles" (count pmids))
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
         (merge content
                 (select-keys x [:article-id :article-subtype :article-type
                                 :dataset-id :external-id :project-id :title])))))

(defmethod enrich-articles "ctgov" [_ articles]
  (vec (for [{:keys [content external-id] :as x} articles]
         (-> (merge content
                    (select-keys x [:article-id :article-subtype :article-type
                                    :dataset-id :external-id :project-id :title]))
             (assoc :uri (str "https://clinicaltrials.gov/ct2/show/" external-id))))))

(defn prepare-ris-content [s]
  (let [{:as ris-map :keys [A1 A2 A3 A4 AU DA JF JO PY UR]} (ris/str->ris-maps s)
        {:keys [abstract primary-title secondary-title]} (ris/titles-and-abstract ris-map)]
    {:abstract abstract
     :authors (concat AU A1 A2 A3 A4)
     :content s
     :date (or (first DA) (first PY))
     :journal-name (or (first JF) (first JO))
     :journal-render (or (first JF) (first JO))
     :primary-title primary-title
     :secondary-title secondary-title
     :urls UR}))

(defn get-entity-content [auth-token endpoint id]
  (let [{:keys [contentUrl mediaType]}
        (dpc/get-dataset-entity
         id "contentUrl externalId mediaType"
         :auth-token auth-token
         :endpoint endpoint)]
    (when (= "application/x-research-info-systems" mediaType)
      (-> (http/get contentUrl
                    {:headers {"Authorization" (str "Bearer " auth-token)}
                     :as :string})
          :body
          prepare-ris-content
          (assoc :content-url contentUrl)))))

(defmethod enrich-articles "datapub" [_ articles]
  (let [{:keys [config]} @@(requiring-resolve 'sysrev.main/system)
        {:keys [graphql-endpoint sysrev-dev-key]} config]
    (vec (for [{:keys [content external-id] :as m} articles]
           (merge content
                  (get-entity-content sysrev-dev-key graphql-endpoint external-id)
                  (select-keys m [:article-id :article-subtype :article-type
                                  :dataset-id :external-id :project-id :title]))))))

(defmethod enrich-articles "pubmed" [_ articles]
  (let [data (->> articles (map :external-id) (fetch-pubmed-articles))]
    (vec (for [{:keys [external-id] :as x} articles]
           (-> (get data (parse-integer external-id))
               (merge (select-keys x [:article-id :project-id]))
               (assoc :uri (str "https://pubmed.ncbi.nlm.nih.gov/" external-id "/")))))))

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
  (->> (q/get-article (distinct article-ids)
                      [:a.* :ad.dataset-id :ad.datasource-name :ad.external-id :ad.content :ad.title
                       :ad.article-subtype :ad.article-type]
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
         (map do-search)
         (apply concat))))

(defn download-file
  "Given a filename and hash, download a file from datasource"
  [{:keys [filename hash]}]
  (:body (http/get (url-join (ds-host) "entity" hash filename)
                   {:as :stream, :headers (auth-header)})))

(defn graphql-query [query & {:keys [host api-key]
                              :or {api-key (ds-auth-key)}}]
  (:body (http/post (str (ds-host) "/graphql")
                    {:headers (auth-header :auth-key api-key)
                     :body (json/write-str {:query (gquery query)})
                     :content-type :application/json
                     :throw-exceptions false
                     :as :json
                     :coerce :always})))

(defn read-account [{:keys [api-key]}]
  (graphql-query [[:account {:apiKey api-key}
                   [:email :apiKey :enabled :password]]]))

(defn create-account! [{:keys [email api-key password]}]
  (graphql-query
   (venia/graphql-query
    {:venia/operation {:operation/type :mutation
                       :operation/name "M"}
     :venia/queries [[:createAccount {:email email
                                      :apiKey api-key
                                      :password password}
                      [:email :apiKey :enabled]]]})))

(defn toggle-account-enabled! [{:keys [api-key]} enabled?]
  (graphql-query
   (venia/graphql-query
    {:venia/operation {:operation/type :mutation
                       :operation/name "M"}
     :venia/queries [[:updateAccount {:apiKey api-key
                                      :enabled enabled?}
                      [:email :apiKey :enabled]]]})))

(defn change-account-password! [{:keys [api-key password]}]
  (graphql-query
   (venia/graphql-query
    {:venia/operation {:operation/type :mutation
                       :operation/name "M"}
     :venia/queries [[:updateAccount {:apiKey api-key
                                      :password password}
                      [:email :apiKey :enabled]]]})))

(defn change-account-email! [{:keys [api-key email]}]
  (graphql-query
   (venia/graphql-query
    {:venia/operation {:operation/type :mutation
                       :operation/name "M"}
     :venia/queries [[:updateAccount {:apiKey api-key
                                      :email email}
                      [:email :apiKey :enabled]]]})))
