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
            [sysrev.shared.article-list :as alist]
            [clojure.tools.logging :as log]))

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
  (let [label-id (project/project-overall-label-id project-id)]
    (http/post
     (str api-host "sysrev/modelService")
     {:content-type "application/json"
      :body (json/write-str
             {"project_id" project-id
              "articles" (->> (get-training-label-values project-id label-id)
                              (mapv (fn [[article-id answer]]
                                      {"article_id" article-id
                                       "label" answer})))
              "feature" (str label-id)})})))

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
          ;; _ (println (-> response :body (json/read-str :key-fn keyword)))
          entries
          (->> (-> response :body (json/read-str :key-fn keyword) :articles)
               (mapv (fn [{:keys [article_id probability]}]
                       {:article-id article_id
                        :value probability})))]
      (predict/store-article-predictions
       project-id predict-run-id label-id entries))))
