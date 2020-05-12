(ns sysrev.biosource.concordance
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.db.core :as db :refer [do-query]]
            [honeysql.helpers :as sqlh :refer [select limit from join insert-into values where]]))

;https://api.insilica.co/service/run/concordance/concordance
(def concordance-route (str api-host "service/run/concordance/concordance"))

(defn munge-sql-body [body]
  (map (fn [row] {
                  :user (first (str/split (:email row) #"@"))
                  :article (:article-id row)
                  :label (:short-label row)
                  :value (str (:answer row))})
       body))

(defn get-request-body [project-id]
  (-> (select :wu.email :article_id :la.short_label :answer)
      (from [:article_label :al])
      (join [:label :la]
            [:= :al.label-id :la.label-id]
            [:web_user :wu]
            [:= :wu.user_id :al.user_id])
      (where [:and
              [:= :project-id project-id]
              [:= :la.value_type "boolean"]
              ])
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