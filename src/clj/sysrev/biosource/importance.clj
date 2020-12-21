(ns sysrev.biosource.importance
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :refer [project-article-count]]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.util :as util]))

(defn- project-sample-article-ids [project-id]
  (let [n-articles (project-article-count project-id)]
    (q/find :article {:project-id project-id :enabled true} :article-id
            :where (when (>= n-articles 1000)
                     [:< :%random (/ 1000.0 n-articles)])
            :limit 1000)))

(defn get-article-text [article-ids]
  (->> (vals (ds-api/get-articles-content article-ids))
       (mapv (fn [{:keys [primary-title secondary-title abstract keywords]}]
               (->> [primary-title secondary-title abstract (str/join " \n " keywords)]
                    (remove empty?)
                    (str/join " \n " ))))))

(defn project-important-terms
  "Given a project, return a map of important term counts from biosource"
  [project-id max-terms]
  (db/with-project-cache project-id [:important-terms max-terms]
    (try (let [article-ids (project-sample-article-ids project-id)
               text (get-article-text article-ids)
               min-count (* 0.05 (count article-ids))
               {:keys [body] :as response}
               (http/post (str api-host "/service/run/importance-2/importance")
                          {:content-type "application/json"
                           :body (json/write-str text)})]
           (cond (str/ends-with? body "Connection refused")
                 {:error ["importance service is temporarily down"]}
                 (< (count text) 5)
                 {:error ["not enough text to build important terms"]}
                 :else
                 (try {:terms (->> (util/read-json body)
                                   (filter #(> (:count %) min-count))
                                   (sort-by :tfidf >)
                                   (take max-terms))}
                      (catch Throwable e
                        (log/warnf "error parsing response:\n%s" (pr-str response))
                        (throw e)))))
         (catch Throwable e
           (if-let [{:keys [status reason-phrase body]} (ex-data e)]
             (do (log/warn "error in fetch-important-terms")
                 (when (or status reason-phrase)      (log/warn status reason-phrase))
                 (when ((some-fn string? nil?) body)  (log/warn body))
                 nil)
             (do (log/warn "unexpected error in fetch-important-terms")
                 (throw e)))))))
