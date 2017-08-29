(ns sysrev.web.routes.api.handlers
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.web-api :as swa]
            [compojure.core :refer :all]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.custom.facts :as facts]
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
             [def-webapi web-api-routes web-api-routes-order]]
            [sysrev.db.labels :as labels]
            [sysrev.db.articles :as articles]))

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
  :copy-articles :post
  {:required [:project-id :src-project-id :article-ids]
   :check-answers? true
   :require-admin? true
   :doc (->> ["\"article-ids\": array of integer article IDs"
              ""
              "Copies a list of articles from `src-project-id` into `project-id`."
              "Returns a map of success/failure counts for articles after copying."]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [api-token project-id src-project-id article-ids allow-answers] :as body}
          (-> request :body)]
      (cond
        (or (not (seqable? article-ids))
            (not (every? integer? article-ids)))
        (make-error-response
         500 :api "article-ids must be an array of integers")
        :else
        (let [results
              (->> article-ids
                   (mapv #(articles/copy-project-article
                           src-project-id project-id %)))
              result-counts
              (->> (distinct results)
                   (map (fn [status]
                          [status (->> results (filter (partial = status)) count)]))
                   (apply concat)
                   (apply hash-map))]
          {:result result-counts})))))

(def-webapi
  :import-pmid-nct-arms :post
  {:required [:project-id :arm-imports]
   :check-answers? true
   :doc (->> ["\"arm-imports\": array of article entries to import"
              "Each entry should have keys: [\"pmid\", \"nct\", \"arm-name\", \"arm-desc\"]"
              "\"pmid\": integer, PubMed ID"
              "\"nct\": string, NCT identifier following format: \"NCT12345\""
              "\"arm-name\": string, name of trial arm"
              "\"arm-desc\": string, description of trial arm"]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [api-token project-id arm-imports] :as body}
          (-> request :body)]
      (cond
        (not (s/valid? ::swa/nct-arm-imports arm-imports))
        (make-error-response
         500 :api (->> ["invalid value for \"arm-imports\":"
                        (s/explain-str ::swa/nct-arm-imports arm-imports)]
                       (str/join "\n")))
        :else
        (do (facts/import-pmid-nct-arms-to-project arm-imports project-id)
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

(def-webapi
  :delete-user :post
  {:required [:email]
   :require-admin? true
   :doc "Deletes user and all entries belonging to the user."}
  (fn [request]
    (let [{:keys [email] :as body}
          (-> request :body)
          {:keys [user-id] :as user}
          (users/get-user-by-email email)]
      (cond
        (nil? user)
        (make-error-response
         500 :api (format "user not found (email=%s)" email))
        :else
        (do (users/delete-user user-id)
            {:result {:success true}})))))

(def-webapi
  :create-user :post
  {:required [:email :password]
   :optional [:permissions]
   :require-admin? true}
  (fn [request]
    (let [{:keys [email password permissions] :as body}
          (-> request :body)
          user (users/get-user-by-email email)]
      (if (nil? user)
        {:result
         {:success true
          :user
          (if (nil? permissions)
            (users/create-user email password)
            (users/create-user email password :permissions permissions))}}
        (make-error-response
         500 :api "A user with that email already exists")))))

(def-webapi
  :create-project :post
  {:required [:project-name]
   :optional [:add-self?]
   :require-admin? true}
  (fn [request]
    (let [{:keys [api-token project-name add-self?] :as body}
          (-> request :body)
          _ (assert (string? project-name))
          {:keys [project-id] :as project}
          (project/create-project project-name)]
      (labels/add-label-entry-boolean
       project-id {:name "overall include"
                   :question "Include this article?"
                   :short-label "Include"
                   :inclusion-value true
                   :required true})
      (project/add-project-note project-id {})
      (when add-self?
        (let [{:keys [user-id]} (users/get-user-by-api-token api-token)]
          (project/add-project-member project-id user-id
                                      :permissions ["member" "admin"])))
      {:result
       {:success true
        :project (select-keys project [:project-id :name])}})))

(def-webapi
  :project-labels :get
  {:required [:project-id]
   :project-role "member"
   :doc "Returns a map of all label definitions for the project."}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (-> request :body)]
      {:result (project/project-labels project-id)})))

(def-webapi
  :delete-label :post
  {:required [:project-id :name]
   :project-role "admin"
   :check-answers? true
   :doc "Deletes entry for label in project. Any answers for the label will be deleted also."}
  (fn [request]
    (let [{:keys [project-id name] :as body}
          (-> request :body)]
      (let [{:keys [label-id] :as entry}
            (-> (q/select-label-where
                 project-id [:= :l.name name] [:*])
                do-query first)]
        (cond
          (nil? entry)
          (make-error-response
           500 :api
           (format "No label found with name: '%s'" name))
          :else
          (do (labels/delete-label-entry project-id label-id)
              {:result {:success true}}))))))

(def-webapi
  :define-label-boolean :post
  {:required [:project-id :name :question :short-label :inclusion-value :required]
   :project-role "admin"
   :check-answers? true
   :doc "Create an entry for a boolean label.
  * `required` is a boolean.
  * `inclusion-value` may be true or false to require that value for inclusion, or null to define no inclusion relationship.
  * descriptive string values:
    * `name` functions primarily as a short internal identifier
    * `short-label` is generally what will be displayed to users
    * `question` is displayed as an longer description of the label"}
  (fn [request]
    (let [{:keys [project-id name question short-label
                  inclusion-value required] :as body}
          (-> request :body)
          _ (do (assert (integer? project-id))
                (assert (string? name))
                (assert (string? question))
                (assert (string? short-label))
                (assert (or (boolean? inclusion-value)
                            (nil? inclusion-value)))
                (assert (or (boolean? required)
                            (nil? required))))
          result (labels/add-label-entry-boolean
                  project-id
                  {:name name :question question :short-label short-label
                   :inclusion-value inclusion-value :required required})]
      {:result result})))

(def-webapi
  :define-label-categorical :post
  {:required [:project-id :name :question :short-label :all-values :required]
   :optional [:inclusion-values :multi?]
   :project-role "admin"
   :check-answers? true
   :doc "Create an entry for a categorical label.
  * `required` is a boolean.
  * `all-values` is a vector of strings listing the allowable values for the label.
  * `inclusion-values` is an optional vector of strings which should be a subset of `all-values` and defines which values are acceptable for inclusion; if `inclusion-values` is present, other values will be treated as implying exclusion.
  * descriptive string values:
    * `name` functions primarily as a short internal identifier
    * `short-label` is generally what will be displayed to users
    * `question` is displayed as an longer description of the label
  * `multi?` is an optional boolean (currently ignored, all labels allow multiple values)."}
  (fn [request]
    (let [{:keys [project-id name question short-label all-values
                  inclusion-values required multi?] :as body}
          (-> request :body)
          _ (do (assert (integer? project-id))
                (assert (string? name))
                (assert (string? question))
                (assert (string? short-label))
                (assert (every? string? all-values))
                (assert (every? string? inclusion-values))
                (assert (or (boolean? required) (nil? required)))
                (assert (or (boolean? multi?) (nil? multi?))))
          result (labels/add-label-entry-categorical
                  project-id
                  {:name name :question question :short-label short-label
                   :all-values all-values :inclusion-values inclusion-values
                   :required required :multi? multi?})]
      {:result result})))

(def-webapi
  :define-label-string :post
  {:required [:project-id :name :question :short-label :max-length :required :multi?]
   :optional [:regex :examples :entity]
   :project-role "admin"
   :check-answers? true
   :doc
   "Creates an entry for a string label definition. Value is provided by user
  in a text input field.

  `max-length` is a required integer.
  `regex` is an optional vector of strings to require that answers must match
  one of the regex values.
  `entity` is an optional string to identify what the value represents.
  `examples` is an optional list of example strings to indicate to users
  the required format.
  `multi?` if true allows multiple string values in answer."}
  (fn [request]
    (let [{:keys [project-id name question short-label max-length regex
                  examples entity required multi?] :as body}
          (-> request :body)
          _ (do (assert (integer? project-id))
                (assert (string? name))
                (assert (string? question))
                (assert (string? short-label))
                (assert (every? string? regex))
                (assert (every? string? examples))
                (assert (or (nil? entity) (string? entity)))
                (assert (or (boolean? required) (nil? required)))
                (assert (or (boolean? multi?) (nil? multi?))))
          result (labels/add-label-entry-string
                  project-id
                  {:name name :question question :short-label short-label
                   :max-length max-length :regex regex :examples examples
                   :entity entity :required required :multi? multi?})]
      {:result result})))
