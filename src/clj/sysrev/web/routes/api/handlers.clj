(ns sysrev.web.routes.api.handlers
  (:require [clojure.spec :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.web-api :as swa]
            [compojure.core :refer :all]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.web.app :refer
             [wrap-permissions current-user-id active-project
              make-error-response]]
            [sysrev.util :refer
             [integerify-map-keys uuidify-map-keys]]
            [clojure.string :as str]
            [sysrev.config.core :refer [env]]
            [org.httpkit.client :as client]
            [clojure.data.json :as json]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.web.routes.api.core :refer
             [def-webapi web-api-routes web-api-routes-order]]))

(def-webapi
  :doc :get
  {:require-token? false
   :doc "Returns a listing of available API calls."}
  (fn [request]
    (let [api-info
          (->>
           @web-api-routes-order
           (mapv
            (fn [name]
              (let [route (get @web-api-routes name)
                    info (select-keys
                          route
                          [:name :method :required :optional
                           :check-answers? :doc])]
                (cond-> info
                  (:check-answers? info)
                  (update :doc #(str % "\n"
                                     "Unless \"allow-answers\" is passed, does nothing if project has any user answers.")))))))]
      {:result {:api api-info}})))

(def-webapi
  :get-api-token :get
  {:required [:email :password]
   :require-token? false
   :doc "Returns an API token for authentication in other API calls."}
  (fn [request]
    (let [{:keys [email password] :as body} (:body request)
          valid (users/valid-password? email password)
          user (when valid (users/get-user-by-email email))
          {verified :verified :or {verified false}} user
          success (boolean (and valid verified))]
      (if success
        {:api-token (:api-token user)}
        (make-error-response 403 :api "User authentication failed")))))

(def-webapi
  :project-info :get
  {:required [:project-id]}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (-> request :body)]
      (let [project (q/query-project-by-id project-id [:*])
            members (-> (select :u.email :u.user-id)
                        (from [:web-user :u])
                        (q/filter-admin-user false)
                        (q/join-user-member-entries project-id)
                        do-query)
            labels (-> (q/select-label-where project-id true [:name])
                       (->> do-query (map :name)))]
        (merge
         (->> [:project-id :name :date-created :settings]
              (select-keys project))
         {:members members
          :articles (project/project-article-count project-id)
          :labels labels})))))

(def-webapi
  :import-pmids :post
  {:required [:project-id :pmids]
   :check-answers? true
   :doc (->> ["\"pmids\": array of integer PubMed IDs"
              ""
              "Imports articles from PubMed."
              "On success, returns the project article count after completing import."]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [api-token project-id pmids allow-answers] :as body}
          (-> request :body)]
      (cond
        (or (not (seqable? pmids))
            (empty? pmids)
            (not (every? integer? pmids)))
        (make-error-response
         500 :api "pmids must be an array of integers")
        :else
        (do (pubmed/import-pmids-to-project pmids project-id)
            {:result
             {:success true
              :project-articles
              (project/project-article-count project-id)}})))))

(def-webapi
  :add-project-member :post
  {:required [:project-id :email]
   :project-role "admin"
   :doc (->> ["Adds user (given by email) to project."
              "Does nothing if user is already a member of project."]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [project-id email] :as body}
          (-> request :body)
          project (q/query-project-by-id project-id [:*])
          {:keys [user-id] :as user}
          (users/get-user-by-email email)]
      (cond
        (nil? user)
        (make-error-response
         500 :api (format "user not found (email=%s)" email))
        (in? (project/project-user-ids project-id) user-id)
        {:result
         {:success true
          :message "User is already a member of project"}}
        :else
        (do (project/add-project-member project-id user-id)
            {:result {:success true}})))))

(def-webapi
  :delete-project :post
  {:required [:project-id]
   :project-role "admin"
   :check-answers? true
   :doc "Deletes project and all database entries belonging to it."}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (-> request :body)]
      (assert project-id)
      (assert (q/query-project-by-id project-id [:project-id]))
      (project/delete-project project-id)
      {:result {:success true}})))

(def-webapi
  :delete-project-articles :post
  {:required [:project-id]
   :project-role "admin"
   :check-answers? true
   :doc "Deletes all articles from project."}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (-> request :body)]
      (assert project-id)
      (assert (q/query-project-by-id project-id [:project-id]))
      (project/delete-project-articles project-id)
      {:result {:success true}})))
