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
            [sysrev.config :as config :refer [env]]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.shared.labels :refer [predictable-label-types]]
            [sysrev.util :as util :refer [in? map-values map-kv uuid-from-string]]))

(defonce predict-api (agent nil))

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
    (let [labels (->> (q/find :label {:project-id 100 :enabled true} :*)
                      (filter #(contains? predictable-label-types (:value-type %))))
          article-ids (->> labels
                           (mapcat #(get-training-article-ids project-id (:label-id %)))
                           distinct)
          texts (prediction-text-for-articles article-ids)
          answers (->> labels
                       (map (fn [{:keys [label-id]}]
                              (let [answers (get-training-label-values project-id label-id)]
                                [label-id answers])))
                       (into {}))
          articles (->> article-ids
                        (map (fn [article-id]
                               (let [tags (->> labels
                                               (mapcat (fn [{:keys [label-id value-type]}]
                                                         (let [answer (get-in answers [label-id article-id])]
                                                           (when (some? answer)
                                                             (let [values (case value-type
                                                                            "categorical" answer
                                                                            "boolean" [(if answer "TRUE" "FALSE")]
                                                                            [])]
                                                               (map #(assoc {} "label_id" (str label-id) "value" %) values)))))))]
                                 {"text" (texts article-id)
                                  "tags" tags}))))]

      {"project_id" (if (= :dev (:profile env)) (* project-id -1) project-id)
       "articles" articles})))

;; Note: the prediction model will need a minimum of an article with a true
;; tag and false tag, otherwise it will fail
(defn create-predict-model [project-id]
  (http/post (str api-host "service/run/modelservice/model/multitask")
             {:content-type "application/json"
              :body (json/write-str (predict-model-request-body project-id))}))

(defn- fetch-model-predictions [project-id article-texts article-ids]
  (log/info "fetching" (count article-ids) "article predictions")
  (->> (-> (http/post (str api-host "service/run/modelservice/predict/multitask")
                      {:content-type "application/json"
                       :body (json/write-str
                              {"project_id" (if (= :dev (:profile env)) (* project-id -1) project-id)
                               "articles" (mapv (fn [article-id]
                                                  {"article_id" (str article-id)
                                                   "text" (get article-texts article-id)})
                                                article-ids)})})
           :body util/read-json)
       (map (fn [{:keys [article_id label_id probability value]}]
              {:article-id (read-string article_id)
               :label-id (uuid-from-string label_id)
               :value probability
               :label-value value}))))

(defn store-model-predictions [project-id & {:keys [predict-version-id]
                                             :or {predict-version-id 3}}]
  (db/with-transaction
    (let [predict-run-id (predict/create-predict-run project-id predict-version-id)]
      (doseq [article-ids (->> (project/project-article-ids project-id true)
                               (partition-all 2000))]
        (let [article-texts (prediction-text-for-articles article-ids)
              entries (fetch-model-predictions project-id article-texts article-ids)]
          (predict/store-article-predictions project-id predict-run-id entries)))
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
