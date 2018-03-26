(ns sysrev.biosource.predict
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
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
            [sysrev.config.core :as config]))

(defonce insilica-api (agent nil))

(def api-host "https://api.insilica.co/")

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

(defn get-article-texts [training? project-id & [label-id]]
  (let [label-id (or label-id (project/project-overall-label-id project-id))]
    (-> (q/select-project-articles
         project-id [:a.article-id :a.primary-title :a.secondary-title
                     :a.abstract :a.keywords])
        (merge-where (if training?
                       [:exists
                        (-> (select :*)
                            (from [:article-label :al])
                            (where [:and
                                    [:= :al.label-id label-id]
                                    [:= :al.article-id :a.article-id]])
                            (q/filter-valid-article-label true))]
                       true))
        (->> do-query
             (mapv (fn [{:keys [article-id primary-title secondary-title
                                abstract keywords]}]
                     [article-id
                      (str/join " \n " [primary-title
                                        secondary-title
                                        abstract
                                        (str/join " \n " keywords)])]))
             (apply concat)
             (apply hash-map)))))

(defn create-predict-model [project-id]
  (let [label-id (project/project-overall-label-id project-id)
        article-texts (get-article-texts true project-id label-id)
        body (json/write-str
              {"project_id" project-id
               "feature" (str label-id)
               "documents" (->> (get-training-label-values project-id label-id)
                                (mapv (fn [[article-id answer]]
                                        {"text" (get article-texts article-id)
                                         "tag" answer}))
                                (filterv #(and (string? (get % "text"))
                                               (boolean? (get % "tag")))))})]
    (http/post
     (str api-host "sysrev/modelService/v2")
     {:content-type "application/json"
      :body body})))

(defn store-model-predictions [project-id & {:keys [label-id predict-version-id]
                                             :or {predict-version-id 3}}]
  (with-transaction
    (let [label-id (or label-id (project/project-overall-label-id project-id))
          predict-run-id (predict/create-predict-run
                          project-id predict-version-id)
          article-texts (get-article-texts false project-id label-id)
          article-ids (vec (keys article-texts))
          response
          (http/post
           (str api-host "sysrev/predictionService")
           {:content-type "application/json"
            :body (json/write-str
                   {"project_id" project-id
                    "feature" (str label-id)
                    "documents" (mapv #(get article-texts %) article-ids)})})
          ;; _ (println (-> response :body))
          entries
          (->> (-> response :body (json/read-str :key-fn keyword) :articles)
               (map-indexed (fn [i {:keys [prediction probability]}]
                              {:article-id (nth article-ids i)
                               :value (if (true? prediction)
                                        probability
                                        (- 1.0 probability))})))]
      (predict/store-article-predictions
       project-id predict-run-id label-id entries)
      (report/update-predict-meta project-id predict-run-id))))

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
             (store-model-predictions project-id)
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
