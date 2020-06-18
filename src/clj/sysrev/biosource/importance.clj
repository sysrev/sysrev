(ns sysrev.biosource.importance
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [honeysql.helpers :refer [select from where limit]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.util :refer [in?]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.util :refer [in? map-values map-kv]]))

(defn- project-article-count [project-id]
  (-> (select :%count.*)(from :article)(where [:and [:= :project-id project-id] [:= :enabled true]])
      do-query (first) (:count)))

(defn- project-article-sample [project-id art-count]
  (let [sample-fraction (/ 1000.0 art-count)]
    (mapv :article-id
          (if
            (< art-count 1000)
            (-> (select :article-id) (from :article)
                (where [:and [:= :project-id project-id][:= :enabled true]]) do-query)
            (-> (select :article-id) (from :article)
                (where [:and [:= :project-id project-id][:= :enabled true][:< :%random sample-fraction]])
                (limit 1000) do-query)))))

(defn get-article-text [article-ids]
  (->> (vals (ds-api/get-articles-content article-ids))
       (mapv (fn [{:keys [primary-title secondary-title abstract keywords]}]
               (->> [primary-title secondary-title abstract (str/join " \n " keywords)]
                    (remove empty?)
                    (str/join " \n " ))))))

(defn get-importance
  "Given a project, return a map of important term counts from biosource"
  [project-id max-terms]
  (try (let [art-count (project-article-count project-id)
             text (get-article-text (project-article-sample project-id art-count))
             min-count (* 0.05 (min 1000 art-count))
             response (http/post (str api-host "/service/run/importance-2/importance")
                                 {:content-type "application/json"
                                  :body (json/write-str text)})]
         (try (->>
                (-> (:body response) (json/read-str :key-fn keyword))
                (filter #(> (:count %) min-count))
                (sort-by :tfidf >)
                (take max-terms))
              (catch Throwable e
                (log/warnf "error parsing response:\n%s" (pr-str response))
                (throw e))))
       (catch Throwable e
         (if-let [{:keys [status reason-phrase body]} (ex-data e)]
           (do (log/warn "error in fetch-important-terms")
               (when (or status reason-phrase)      (log/warn status reason-phrase))
               (when ((some-fn string? nil?) body)  (log/warn body))
               nil)
           (do (log/warn "unexpected error in fetch-important-terms")
               (throw e))))))
