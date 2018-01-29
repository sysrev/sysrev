(ns sysrev.predict.api
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [sysrev.db.queries :as q]))

(def api-host "http://api.insilica.co/")

(defn create-predict-model [project-id]
  (http/post (str api-host "sysrev/modelService")
             {:content-type "application/json"
              :body (json/write-str
                     {"projectId" project-id})}))

(defn store-model-predictions [project-id]
  (let [articles (-> (q/select-project-articles
                      project-id [:a.article-id :a.abstract]
                      {:include-disabled? true}))
        response
        (http/post
         (str api-host "sysrev/predictionService")
         {:content-type "application/json"
          :body (json/write-str
                 {"projectId" project-id
                  "articles" (->> articles
                                  (remove #(-> % :abstract empty?))
                                  (mapv (fn [{:keys [article-id abstract]}]
                                          {"id" article-id
                                           "text" abstract})))})})]
    ;; TODO: store results from response
    response))
