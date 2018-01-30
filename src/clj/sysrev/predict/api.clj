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
            [sysrev.predict.core :as predict]))

(def api-host "http://api.insilica.co/")

(defn create-predict-model [project-id]
  (http/post (str api-host "sysrev/modelService")
             {:content-type "application/json"
              :body (json/write-str
                     {"projectId" project-id})}))

(defn store-model-predictions [project-id label-id & {:keys [predict-version-id]
                                                      :or {predict-version-id 3}}]
  (with-transaction
    (let [label-id (or label-id (project/project-overall-label-id project-id))
          predict-run-id (predict/create-predict-run
                          project-id predict-version-id)
          response
          (http/post
           (str api-host "sysrev/predictionService")
           {:content-type "application/json"
            :body (json/write-str
                   {"projectId" project-id})})
          entries
          (->> (-> response :body (json/read-str :key-fn keyword) :articles)
               (mapv (fn [{:keys [articleId label prediction probability]}]
                       {:article-id articleId
                        :value probability})))]
      (predict/store-article-predictions
       project-id predict-run-id label-id entries))))
