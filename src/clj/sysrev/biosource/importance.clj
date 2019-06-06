(ns sysrev.biosource.importance
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction
              with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values parse-number in?]]
            [sysrev.config.core :as config]
            [sysrev.biosource.core :refer [api-host]]))

(defonce importance-api (agent nil))

(defonce importance-loading (atom {}))

(defn record-importance-load-start [project-id]
  (swap! importance-loading update project-id
         #(if (nil? %) 1 (inc %))))

(defn record-importance-load-stop [project-id]
  (swap! importance-loading update project-id
         #(if (nil? %) nil (dec %))))

(defn project-importance-loading? [project-id]
  (let [load-count (get @importance-loading project-id)]
    (and (integer? load-count) (>= load-count 1))))

(defn project-important-terms [project-id]
  (with-project-cache project-id [:important-terms]
    (-> (select :entity-type :instance-name :instance-count :instance-score)
        (from :project-entity)
        (where [:= :project-id project-id])
        (->> do-query (group-by #(-> % :entity-type keyword))))))

(defn fetch-important-terms
  "Given a coll of pmids, return a map of important term counts from biosource"
  [pmids]
  (try (-> (http/post (str api-host "sysrev/importance")
                      {:content-type "application/json"
                       :body (json/write-str pmids)})
           :body (json/read-str :key-fn keyword))
       (catch Throwable e
         (if-let [{:keys [status reason-phrase body]} (ex-data e)]
           (do (log/warn "error in fetch-important-terms")
               (when (or status reason-phrase)      (log/warn status reason-phrase))
               (when ((some-fn string? nil?) body)  (log/warn body))
               nil)
           (do (log/warn "unexpected error in fetch-important-terms")
               (throw e))))))

(defn load-project-important-terms
  "Queries important terms for `project-id` from Insilica API
  and stores results in local database."
  [project-id]
  (try (when (project/project-exists? project-id :include-disabled? true)
         (record-importance-load-start project-id)
         (clear-project-cache project-id)
         (let [max-count 100
               pmids (project/project-pmids project-id)
               response-entries (some-> pmids not-empty fetch-important-terms)
               ;; currently the response will only contain mesh entries
               entries
               (some->> response-entries
                        (mapv (fn [{:keys [term count tfidf uri]}]
                                {:entity-type "mesh"
                                 :instance-name term
                                 :instance-count count
                                 :instance-score tfidf}))
                        (sort-by :instance-score >)
                        (take max-count)
                        (filter #(and %
                                      (-> % :entity-type string?)
                                      (-> % :instance-name string?)
                                      (-> % :instance-count integer?)
                                      (or (-> % :instance-score number?)
                                          (-> % :instance-score nil?))))
                        (mapv #(assoc % :project-id project-id)))]
           (with-transaction
             (when (and (not-empty entries)
                        (project/project-exists? project-id :include-disabled? true))
               (-> (delete-from :project-entity)
                   (where [:= :project-id project-id])
                   do-execute)
               (doseq [entries-group (partition-all 500 entries)]
                 (-> (insert-into :project-entity)
                     (values entries-group)
                     do-execute))))
           nil))
       (catch Throwable e
         (let [msg (.getMessage e)]
           (if (and (string? msg) (some #(str/includes? msg %)
                                        ["Connection is closed"
                                         "This statement has been closed"]))
             (log/info "load-project-important-terms: DB connection closed")
             (do (log/info "Exception in load-project-important-terms:" msg)
                 (.printStackTrace e))))
         nil)
       (finally (record-importance-load-stop project-id)
                (clear-project-cache project-id))))

(defn schedule-important-terms-update [project-id]
  (when-not (in? [:test :remote-test] (-> config/env :profile))
    (send importance-api (fn [_] (load-project-important-terms project-id)))))

(defn force-importance-update-all-projects []
  (let [project-ids (project/all-project-ids)]
    (log/info "Updating important terms for projects:" (pr-str project-ids))
    (doseq [project-id project-ids]
      (log/info "Loading for project #" project-id "...")
      (load-project-important-terms project-id))
    (log/info "Finished updating predictions for" (count project-ids) "projects")))
