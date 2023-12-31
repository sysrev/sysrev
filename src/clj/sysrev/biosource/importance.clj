(ns sysrev.biosource.importance
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :refer [project-article-count]]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.datasource.api :as ds-api]
            [honeysql.helpers :as sqlh :refer [insert-into values select from where limit order-by]]
            [honeysql-postgres.helpers :as psqlh]
            [sysrev.util :as util]
            [clojure.set]))

(defn- project-sample-article-ids [project-id]
  (let [n-articles (project-article-count project-id)]
    (q/find :article {:project-id project-id :enabled true} :article-id
            :where (when (>= n-articles 350)
                     [:< :%random (/ 350.0 n-articles)])
            :limit 350)))

(defn get-article-text [article-ids]
  (->> (vals (ds-api/get-articles-content article-ids))
       (mapv (fn [{:keys [primary-title secondary-title abstract keywords]}]
               (->> [primary-title secondary-title abstract (str/join " \n " keywords)]
                    (remove empty?)
                    (str/join " \n "))))))

(defn- upsert-terms-and-join-term-id [terms]
  (-> (insert-into :important-terms)
      (values (mapv (fn [term] {:term (:term term)}) terms))
      (psqlh/upsert (-> (psqlh/on-conflict :term) (psqlh/do-nothing)))
      db/do-execute)
  (-> (select :term-id :term) (from :important-terms)
      (where [:in :term (mapv :term terms)])
      db/do-query
      (clojure.set/join terms)))

(defn replace-project-important-term-tfidf [project-id term-tfidf]
  (when (seq term-tfidf)
    (let [term-id-tfidf (upsert-terms-and-join-term-id term-tfidf)
          insert-vals (mapv (fn [t] {:project-id project-id :term-id (:term-id t) :tfidf (:tfidf t)}) term-id-tfidf)]
      (-> (sqlh/delete-from :project-important-terms) (where [:= :project-id project-id]) db/do-execute)
      (-> (insert-into :project-important-terms) (values insert-vals) db/do-execute))))

(defn- project-last-source-update [project-id]
  (some-> (select [:%max.date-created :max-date])
          (from :project-source)
          (where [:= :project-id project-id])
          (limit 1)
          db/do-query
          first
          :max-date
          inst-ms))

(defn- project-important-terms-last-update [project-id]
  (some-> (select :created)
          (from :project-important-terms)
          (where [:= :project-id project-id])
          (limit 1)
          db/do-query
          first
          :created
          inst-ms))

(defn should-update-terms? [project-id]
  (let [last-source-update (project-last-source-update project-id)
        last-term-update (project-important-terms-last-update project-id)]
    (cond
      (#{:dev :test} (:profile env)) false
      (nil? last-source-update) false ; No sources - so there can't be important terms
      (nil? last-term-update) true ; terms have never been updated - need to update
      :else (> last-source-update last-term-update)))) ; if sources updated more recently than terms, then terms need update

(defn update-project-terms [project-id]
  (let [article-ids (project-sample-article-ids project-id)
        text (get-article-text article-ids)
        min-count (* 0.05 (count article-ids))
        {:keys [body]}
        (http/post (str api-host "/service/run/importance-2/importance")
                   {:content-type "application/json"
                    :body (json/write-str text)})]
    (cond (str/ends-with? body "Connection refused")
          {:error {:message "importance service is temporarily down"}}

          (< (count text) 5)
          {:terms nil} ;; not enough text to build important terms

          :else
          (replace-project-important-term-tfidf project-id
                                                (->> (util/read-json body)
                                                     (filter #(> (:count %) min-count))
                                                     (sort-by :tfidf >))))))

(defn lookup-important-terms [project-id max-terms]
  (-> (select :term :tfidf) (from [:project-important-terms :pit])
      (sqlh/join [:important-terms :it] [:= :pit.term-id :it.term-id])
      (where [:= :project-id project-id])
      (order-by [:tfidf :desc])
      (limit max-terms)
      db/do-query))

(defn project-important-terms
  "Given a project, return a map of important term counts from biosource"
  [project-id max-terms]
  (when (should-update-terms? project-id) (update-project-terms project-id))
  {:terms (lookup-important-terms project-id max-terms)})
