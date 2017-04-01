(ns sysrev.web.routes.api
  (:require [compojure.core :refer :all]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.web.app :refer
             [wrap-permissions current-user-id active-project]]
            [sysrev.util :refer
             [integerify-map-keys uuidify-map-keys]]
            [clojure.string :as str]
            [sysrev.config.core :refer [env]]
            [org.httpkit.client :as client]
            [clojure.data.json :as json]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defmacro check-allow-answers
  "Returns an error response if the project-id of request contains any user
  label answers, unless the request passed value true for :allow-answers arg."
  [request & body]
  `(let [request# ~request
         body-fn# #(do ~@body)
         allow-answers# (-> request# :body :allow-answers)
         project-id# (-> request# :body :project-id)
         project# (and project-id# (q/query-project-by-id project-id# [:*]))
         answers-count# (and project-id# project#
                             (-> (q/select-project-article-labels
                                  project-id# nil [:%count.*])
                                 do-query first :count))]
     (cond
       (nil? project-id#)
       {:error
        {:status 403
         :type :api
         :message "No project-id given"}}
       (nil? project#)
       {:error
        {:status 403
         :type :api
         :message (format "No project with id=%s" project-id#)}}
       (and (not (true? allow-answers#))
            (not= 0 answers-count#))
       {:error
        {:status 403
         :type :api
         :message
         (->> [(format "Project name: \"%s\"" (:name project#))
               (format "Project contains %d entries of user answers."
                       answers-count#)
               "To confirm this is intended, include argument \"allow-answers\" with true value in your request."]
              (str/join "\n"))}}
       :else
       (body-fn#))))

(defroutes api-routes
  (GET "/web-api/doc" request
       {:result
        {:api
         [{:get-api-token
           {:method :GET
            :required [:email :password]
            :doc "Returns an API token for authentication in other API calls."}}
          {:project-info
           {:method :GET
            :required [:api-token :project-id]}}
          {:import-pmids
           {:method :POST
            :required [:api-token :project-id :pmids]
            :optional [:allow-answers]
            :doc (->> ["\"pmids\": array of integer PubMed IDs"
                       ""
                       "Imports articles from PubMed."
                       "On success, returns the project article count after completing import."
                       "Unless \"allow-answers\" is passed, does nothing if project has any user answers."]
                      (str/join "\n"))}}
          {:add-project-member
           {:method :POST
            :required [:api-token :project-id :email]
            :doc (->> ["Adds user (given by email) to project."
                       "Does nothing if user is already a member of project."]
                      (str/join "\n"))}}
          {:delete-project
           {:method :POST
            :required [:api-token :project-id]
            :optional [:allow-answers]
            :doc (->> ["Deletes project and all database entries belonging to it."
                       "Unless \"allow-answers\" is passed, does nothing if project has any user answers."]
                      (str/join "\n"))}}
          {:delete-project-articles
           {:method :POST
            :required [:api-token :project-id]
            :optional [:allow-answers]
            :doc (->> ["Deletes all articles from project."
                       "Unless \"allow-answers\" is passed, does nothing if project has any user answers."]
                      (str/join "\n"))}}]}})
  (GET "/web-api/get-api-token" request
       (let [request (dissoc request :session)
             {:keys [email password] :as body} (:body request)
             valid (users/valid-password? email password)
             user (when valid (users/get-user-by-email email))
             {verified :verified :or {verified false}} user
             success (boolean (and valid verified))]
         (if success
           {:success true
            :api-token (:api-token user)}
           {:error
            {:status 403
             :type :api
             :message "User authentication failed"}})))
  (GET "/web-api/project-info" request
       (let [request (dissoc request :session)]
         (wrap-permissions
          request ["admin"] []
          (let [{:keys [project-id] :as body}
                (-> request :body)]
            (assert project-id)
            (let [project (q/query-project-by-id project-id [:*])
                  _ (assert project)
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
                :labels labels}))))))
  (POST "/web-api/import-pmids" request
        (let [request (dissoc request :session)]
          (wrap-permissions
           request ["admin"] []
           (check-allow-answers
            request
            (let [{:keys [api-token project-id pmids allow-answers] :as body}
                  (-> request :body)
                  project (q/query-project-by-id project-id [:*])]
              (cond
                (nil? project)
                {:error {:status 500
                         :type :api
                         :message
                         (format "project not found (id=%s)" project-id)}}
                (or (not (seqable? pmids))
                    (empty? pmids)
                    (not (every? integer? pmids)))
                {:error {:status 500
                         :type :api
                         :message "pmids must be an array of integers"}}
                :else
                (do (pubmed/import-pmids-to-project pmids project-id)
                    {:result
                     {:success true
                      :project-articles
                      (project/project-article-count project-id)}})))))))
  (POST "/web-api/add-project-member" request
        (let [request (dissoc request :session)]
          (wrap-permissions
           request ["admin"] []
           (let [{:keys [api-token project-id email] :as body}
                 (-> request :body)
                 project (q/query-project-by-id project-id [:*])
                 {:keys [user-id] :as user}
                 (users/get-user-by-email email)]
             (cond
               (nil? project)
               {:error {:status 500
                        :type :api
                        :message
                        (format "project not found (id=%s)" project-id)}}
               (nil? user)
               {:error {:status 500
                        :type :api
                        :message
                        (format "user not found (email=%s)" email)}}
               (in? (project/project-user-ids project-id) user-id)
               {:result {:success true
                         :message "User is already a member of project"}}
               :else
               (do (project/add-project-member project-id user-id)
                   {:result {:success true}}))))))
  (POST "/web-api/delete-project" request
        (let [request (dissoc request :session)]
          (wrap-permissions
           request ["admin"] []
           (check-allow-answers
            request
            (let [{:keys [project-id] :as body}
                  (-> request :body)]
              (assert project-id)
              (assert (q/query-project-by-id project-id [:project-id]))
              (project/delete-project project-id)
              {:result {:success true}})))))
  (POST "/web-api/delete-project-articles" request
        (let [request (dissoc request :session)]
          (wrap-permissions
           request ["admin"] []
           (check-allow-answers
            request
            (let [{:keys [project-id] :as body}
                  (-> request :body)]
              (assert project-id)
              (assert (q/query-project-by-id project-id [:project-id]))
              (project/delete-project-articles project-id)
              {:result {:success true}}))))))

;; HTTP client functions for testing API handlers
(defn webapi-request [method route body & {:keys [host port url]}]
  (let [port (or port (-> env :server :port))
        host (or host "localhost")]
    (-> @(client/request
          {:url (if url
                  (format "%sweb-api/%s" url route)
                  (format "http://%s:%d/web-api/%s"
                          host port route))
           :method method
           :body (json/write-str body)
           :headers {"Content-Type" "application/json"}})
        :body (json/read-str :key-fn keyword))))
(defn webapi-get [route body & opts]
  (apply webapi-request :get route body opts))
(defn webapi-post [route body & opts]
  (apply webapi-request :post route body opts))
