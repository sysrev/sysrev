(ns sysrev.datasource.api
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [venia.core :refer [graphql-query]]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.datasource.core :as ds]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer
             [assert-pred map-keys parse-integer apply-keyargs]]))

(defonce ds-host-override (atom nil))
(defonce ds-auth-key-override (atom nil))

(defn ds-auth-key []
  (or @ds-auth-key-override (env :datasource-auth-key)))

(defn ds-host []
  (or @ds-host-override (env :datasource-host) "https://datasource.insilica.co"))

(def academic-fields
  [:primary-title :secondary-title :date :urls :document-ids :abstract :keywords
   :year :notes :remote-database-name :authors :work-type])

(defn field-to-graphql [field]
  (some-> field db/clj-identifier-to-sql keyword))

(defn field-from-graphql [field]
  (some-> field db/sql-identifier-to-clj keyword))

(defn all-pubmed-fields []
  (let [unused #{:urls :document-ids :notes :remote-database-name :work-type}]
    (remove #(contains? unused %) academic-fields)))

(defn- auth-header []
  (let [auth-key (->> (ds-auth-key) (assert-pred string?) (assert-pred not-empty))]
    {"Authorization" (str "Bearer " auth-key)}))

(defn run-ds-query [query & {:keys [host]}]
  (http/post (str (or host (ds-host)) "/graphql")
             {:headers (auth-header)
              :body (json/write-str {:query query})
              :as :json}))

(defn-spec parse-ds-response (s/nilable sequential?)
  [{:keys [status body] :as response} map?
   extract-result ifn?]
  (when-not (= status 200)
    (throw (ex-info (str "datasource responded with status " status)
                    {:type ::response-status})))
  (->> (try (extract-result body)
            (catch Throwable cause
              (throw (ex-info (str "exception in extract-result: " (.getMessage cause))
                              {:type ::response-parse} cause))))
       (mapv #(map-keys field-from-graphql %))))

(defn fetch-pubmed-articles [pmids & {:keys [fields]}]
  (let [pmids (mapv parse-integer pmids)]
    (assert (every? integer? pmids))
    #_ (log/infof "fetching %d pubmed articles" (count pmids))
    (zipmap pmids (-> (graphql-query
                       {:venia/queries
                        [[:pubmedEntities {:pmids pmids}
                          (-> (mapv field-to-graphql (or fields (all-pubmed-fields)))
                              (conj :id))]]})
                      (run-ds-query)
                      (parse-ds-response #(get-in % [:data :pubmedEntities]))))))

(defn fetch-pubmed-article [pmid & {:as opts}]
  (let [pmid (parse-integer pmid)]
    (-> (apply-keyargs fetch-pubmed-articles [pmid] opts)
        (get pmid))))

(defn get-articles-content [article-ids]
  (let [articles (q/find [:article :a] {:article-id article-ids}
                         [:a.* :ad.datasource-name :ad.external-id :ad.content]
                         :join [:article-data:ad :a.article-data-id])
        pmids (->> articles
                   (map #(when (ds/pubmed-data? %)
                           (parse-integer (:external-id %))))
                   (remove nil?))
        data (some-> pmids fetch-pubmed-articles)]
    (zipmap (map :article-id articles)
            (mapv #(merge (select-keys % [:article-id :project-id])
                          (or (get data (-> % :external-id parse-integer))
                              (:content %)))
                  articles))))

(defn get-article-content [article-id]
  (-> (get-articles-content [article-id])
      (get article-id)))
