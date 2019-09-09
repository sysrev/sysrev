(ns sysrev.datasource.api
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [venia.core :refer [graphql-query]]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [assert-pred map-keys]]))

(defonce auth-key-override (atom nil))

(defn get-auth-key []
  (or @auth-key-override (env :sysrev-dev-key)))

(def academic-fields [:primary-title :secondary-title :date :urls :document-ids
                      :abstract :keywords :year :notes :remote-database-name
                      :authors :work-type])

(defn field-to-graphql [field]
  (some-> field db/clj-identifier-to-sql keyword))

(defn field-from-graphql [field]
  (some-> field db/sql-identifier-to-clj keyword))

(defn all-pubmed-fields []
  (let [unused #{:urls :document-ids :notes :remote-database-name :work-type :duplicate-of}]
    (remove #(contains? unused %) academic-fields)))

(defn- auth-header []
  (let [auth-key (->> (get-auth-key) (assert-pred string?) (assert-pred not-empty))]
    {"Authorization" (str "Bearer " auth-key)}))

(defn run-ds-query [query & {:keys [host] :or {host "https://datasource.insilica.co"}}]
  (http/post (str host "/graphql") {:headers (auth-header)
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

(defn fetch-pubmed-articles [pmids]
  (-> (graphql-query {:venia/queries [[:pubmedEntities {:pmids pmids}
                                       (-> (mapv field-to-graphql (all-pubmed-fields))
                                           (conj :id))]]})
      (run-ds-query)
      (parse-ds-response #(get-in % [:data :pubmedEntities]))))
