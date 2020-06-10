(ns sysrev.biosource.concordance
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.db.core :as db :refer [do-query]]
            [honeysql.helpers :as sqlh :refer [select limit from join insert-into values where]]))

;https://api.insilica.co/service/run/concordance/concordance
(def concordance-route (str api-host "service/run/concordance-2/concordance"))

(defn munge-sql-body [body]
  (map (fn [row] {
                  :user-id (:user-id row)
                  :article (:article-id row)
                  :label-id (:label-id row)
                  :value (str (:answer row))})
       body))

(defn get-request-body [project-id]
  (-> (select :user_id :article_id :al.label_id :answer)
      (from [:article_label :al])
      (join [:label :la] [:= :al.label-id :la.label-id])
      (where [:and
              [:= :project-id project-id]
              [:= :la.value_type "boolean"]
              [:= :al.resolve false]])
      do-query
      munge-sql-body
      json/write-str))

(defn get-concordance
  "Given a project, return a vector of user-user-label concordances"
  [project-id]
  (-> (http/post concordance-route
                  {:content-type "application/json" :body (get-request-body project-id)})
       :body
       (json/read-str :key-fn keyword)))