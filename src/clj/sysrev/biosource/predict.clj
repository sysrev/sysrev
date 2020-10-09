(ns sysrev.biosource.predict
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.label.core :as label]
            [sysrev.predict.core :as predict]
            [sysrev.predict.report :as report]
            [sysrev.config :as config]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.util :as util :refer [in? map-values map-kv]]))

(defonce predict-api (agent nil))

;; TODO: this only works for boolean inclusion criteria labels
(defn- get-training-label-values [project-id label-id]
  (db/with-transaction
    (->> (label/query-public-article-labels project-id)
         (map-kv (fn [article-id {:keys [labels]}]
                   (let [consensus (label/article-consensus-status project-id article-id)
                         labels (get labels label-id)
                         answer (cond (in? [:single :consistent] consensus)
                                      (->> labels (map :answer) (remove nil?) first)
                                      (= :resolved consensus)
                                      (-> (label/article-resolved-labels
                                           project-id article-id)
                                          (get label-id)))]
                     (when-not (nil? answer)
                       [article-id answer])))))))

(defn- get-training-article-ids [project-id label-id]
  (q/find-article {:project-id project-id} :article-id, :with []
                  :where (q/exists [:article-label :al]
                                   {:al.article-id :a.article-id, :al.label-id label-id}
                                   :prepare #(q/filter-valid-article-label % true))))

(defn- prediction-text-for-articles [article-ids]
  (->> (ds-api/get-articles-content article-ids)
       (map-values (fn [{:keys [primary-title secondary-title abstract keywords]}]
                     (->> [primary-title secondary-title abstract (str/join " \n " keywords)]
                          (remove empty?)
                          (str/join " \n " ))))))

(defn- predict-model-request-body [project-id]
  (db/with-transaction
    (let [label-id (project/project-overall-label-id project-id)
          article-texts (->> (get-training-article-ids project-id label-id)
                             (prediction-text-for-articles))]
      {"project_id" project-id
       "feature" (str label-id)
       "documents" (->> (get-training-label-values project-id label-id)
                        (mapv (fn [[article-id answer]]
                                {"text" (get article-texts article-id)
                                 "tag" answer}))
                        (filterv #(and (string? (get % "text"))
                                       (boolean? (get % "tag")))))})))

;; Note: the prediction model will need a minimum of an article with a true
;; tag and false tag, otherwise it will fail
(defn create-predict-model [project-id]
  (http/post (str api-host "sysrev/modelService/v2")
             {:content-type "application/json"
              :body (json/write-str (predict-model-request-body project-id))}))

(defn- fetch-model-predictions [project-id label-id article-texts article-ids]
  (log/info "fetching" (count article-ids) "article predictions")
  (->> (-> (http/post (str api-host "sysrev/predictionService")
                      {:content-type "application/json"
                       :body (json/write-str
                              {"project_id" project-id
                               "feature" (str label-id)
                               "documents" (mapv #(get article-texts %) article-ids)})})
           :body (json/read-str :key-fn keyword) :articles)
       (map-indexed (fn [i {:keys [prediction probability]}]
                      {:article-id (nth article-ids i)
                       :value (if (true? prediction)
                                probability
                                (- 1.0 probability))}))))

(defn store-model-predictions [project-id & {:keys [label-id predict-version-id]
                                             :or {predict-version-id 3}}]
  (db/with-transaction
    (let [label-id (or label-id (project/project-overall-label-id project-id))
          predict-run-id (predict/create-predict-run project-id predict-version-id)]
      (doseq [article-ids (->> (project/project-article-ids project-id true)
                               (partition-all 2000))]
        (let [article-texts (prediction-text-for-articles article-ids)
              entries (fetch-model-predictions project-id label-id article-texts article-ids)]
          (predict/store-article-predictions project-id predict-run-id label-id entries)))
      (report/update-predict-meta project-id predict-run-id))))

(defn update-project-predictions [project-id]
  (let [{:keys [reviewed]} (label/project-article-status-counts project-id)]
    (when (and reviewed (>= reviewed 10))
      (send predict-api
            (fn [_] (try (db/with-transaction
                           (create-predict-model project-id)
                           (store-model-predictions project-id)
                           true)
                         (catch Throwable e
                           (util/log-exception e)
                           false))))
      (await predict-api)
      (when (true? @predict-api)
        (q/project-latest-predict-run-id project-id)))))

(defn schedule-predict-update [project-id]
  (when (= :prod (-> config/env :profile))
    (future (update-project-predictions project-id))))

(defn ^:repl force-predict-update-all-projects []
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
