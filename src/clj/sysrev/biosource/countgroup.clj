(ns sysrev.biosource.countgroup
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [sysrev.db.queries :as q]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.util :as util]))

;https://api.insilica.co/service/run/concordance/concordance
(def countgroup-route (str api-host "service/run/countgroup/countgroup"))

(defn- munge-sql-body [body]
  (map (fn [{:keys [answer] :as row}]
         (-> (select-keys row [:user-id :article-id :label-id])
             (assoc :answer (cond (boolean? answer)  [(str answer)]
                                  (string? answer)   [(str answer)]
                                  :else              answer))))
       (filter #(some? (:answer %)) body)))

(defn- project-answer-count [project-id]
  (q/find-count [:article-label :al] {:project-id project-id}
                :join [[:label :l] :al.label-id]))

(defn- get-request-body [project-id sample-fraction]
  (-> (q/find [:article-label :al] {:project-id project-id
                                    :value-type ["boolean" "categorical"]}
              [:article-id :user-id :al.label-id :answer]
              :join [[:label :l] :al.label-id]
              :where [:and [:<> :answer nil]
                      [:< :%random sample-fraction]])
      munge-sql-body
      json/write-str))

(defn get-label-countgroup
  "Given a project, return a map of
  {:answers [[user label answer] ...] :art-count 10}"
  [project-id]
  (let [sample-fraction (min 1.0 (/ 20000.0 (project-answer-count project-id)))]
    (-> (http/post countgroup-route
                   {:content-type "application/json"
                    :body (get-request-body project-id sample-fraction)})
        :body
        (util/read-json)
        (merge {:sampled (< sample-fraction 1.0)}))))
