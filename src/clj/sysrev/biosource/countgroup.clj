(ns sysrev.biosource.countgroup
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.db.core :as db :refer [do-query]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [select limit from join insert-into values where order-by]]))

;https://api.insilica.co/service/run/concordance/concordance
(def countgroup-route (str api-host "service/run/countgroup/countgroup"))

(defn munge-sql-body [body]
  (map (fn [row]
         {:user-id        (:user-id row)
          :article-id     (:article-id row)
          :label-id       (:label-id row)
          :answer         (cond
                            (boolean? (:answer row))  [(str (:answer row))]
                            (string?  (:answer row))  [(str (:answer row))]
                            :else                      (:answer row))})
       (filter #(not= (:answer %) nil) body)))

(defn project-answer-count [project-id]
  (-> (select :%count.*)
      (from [:article_label :al])
      (join [:label :la] [:= :al.label-id :la.label-id])
      (where [:= :project-id project-id])
      do-query
      (first)
      (:count)))

(defn get-request-body [project-id sample-fraction]
    (-> (select :al.article-id :al.user-id :al.label-id :al.answer)
        (from [:article_label :al])
        (join [:label :la] [:= :al.label-id :la.label-id])
        (where [:and
                [:= :project-id project-id]
                [:in :la.value_type `("boolean" "categorical")]
                [:<> :answer nil]
                [:< :%random sample-fraction]])
        do-query
        munge-sql-body
        json/write-str))

(defn get-label-countgroup
  "Given a project, return a map of {:answers [[user label answer][user label answer]] :art-count 10}"
  [project-id]
  (let [sample-fraction (min 1.0 (/ 20000.0 (project-answer-count project-id)))]
    (-> (http/post countgroup-route {:content-type "application/json" :body (get-request-body project-id sample-fraction)})
        :body
        (json/read-str :key-fn keyword)
        (merge {:sampled (< sample-fraction 1.0)}))))