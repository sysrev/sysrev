(ns sysrev.predict.api
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer [do-query do-execute with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.predict.core :as predict]
            [sysrev.predict.report :as report]
            [sysrev.shared.article-list :as alist]
            [sysrev.config.core :as config]
            [clojure.tools.logging :as log]))

(defonce insilica-api (agent nil))

(def api-host "http://api.insilica.co/")

;; TODO: this only works for boolean inclusion criteria labels
(defn get-training-label-values [project-id label-id]
  (->> (labels/query-public-article-labels project-id)
       (map (fn [[article-id {:keys [labels]}]]
              (let [labels (get labels label-id)
                    answer
                    (cond (empty? labels) nil

                          (or (alist/is-single? labels)
                              (alist/is-consistent? labels))
                          (->> labels first :answer)

                          (alist/is-resolved? labels)
                          (->> labels (filter :resolve) first :answer)

                          :else nil)]
                (when-not (nil? answer)
                  [article-id answer]))))
       (remove nil?)
       (apply concat)
       (apply hash-map)))

(defn create-predict-model [project-id]
  (let [label-id (project/project-overall-label-id project-id)
        body (json/write-str
              {"project_id" project-id
               "articles" (->> (get-training-label-values project-id label-id)
                               (mapv (fn [[article-id answer]]
                                       {"article_id" article-id
                                        "label" answer})))
               "feature" (str label-id)})]
    (http/post
     (str api-host "sysrev/modelService")
     {:content-type "application/json"
      :body body})))

(defn store-model-predictions [project-id label-id & {:keys [predict-version-id]
                                                      :or {predict-version-id 3}}]
  (with-transaction
    (let [label-id (or label-id (project/project-overall-label-id project-id))
          predict-run-id (predict/create-predict-run
                          project-id predict-version-id)
          response
          (http/get
           (str api-host "sysrev/predictionService")
           {:content-type "application/json"
            :query-params {"project_id" project-id
                           "feature" (str label-id)}})
          ;; _ (println (-> response :body))
          entries
          (->> (-> response :body (json/read-str :key-fn keyword) :articles)
               (mapv (fn [{:keys [article_id probability prediction]}]
                       {:article-id article_id
                        :value (if (true? prediction)
                                 probability
                                 (- 1.0 probability))})))]
      (predict/store-article-predictions
       project-id predict-run-id label-id entries))))

(defn update-project-predictions [project-id]
  (let [reviewed (-> (labels/project-article-status-counts project-id)
                     :reviewed)]
    (when (and reviewed (>= reviewed 10))
      (send
       insilica-api
       (fn [_]
         (try
           (with-transaction
             (create-predict-model project-id)
             (store-model-predictions project-id nil)
             (let [predict-run-id (q/project-latest-predict-run-id project-id)]
               (report/update-predict-meta project-id predict-run-id))
             true)
           (catch Throwable e
             (log/info "Exception in update-project-predictions:")
             (log/info (.getMessage e))
             (.printStackTrace e)
             false))))
      (await insilica-api)
      (when (true? @insilica-api)
        (q/project-latest-predict-run-id project-id)))))

(defn schedule-predict-update [project-id]
  (when (= :prod (-> config/env :profile))
    (future (update-project-predictions project-id))))

(defn force-predict-update-all-projects []
  (let [project-ids (project/all-project-ids)]
    (log/info "Updating predictions for projects:"
              (pr-str project-ids))
    (doseq [project-id project-ids]
      (log/info "Loading for project #" project-id "...")
      (let [predict-run-id (update-project-predictions project-id)]
        (if (nil? predict-run-id)
          (log/info "... no predictions loaded")
          (let [meta (report/predict-summary predict-run-id)]
            (if (empty? meta)
              (log/info "... predict results not found (?)")
              (log/info "... success:" meta))))))
    (log/info "Finished updating predictions for"
              (count project-ids) "projects")))
