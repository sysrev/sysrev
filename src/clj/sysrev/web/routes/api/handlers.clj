(ns sysrev.web.routes.api.handlers
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [compojure.core :refer :all]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.api :as api]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.web-api :as swa]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.label.core :as labels]
            [sysrev.clone-project :as clone]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]
            [sysrev.predict.core :as predict]
            [sysrev.web.app :refer
             [current-user-id active-project make-error-response]]
            [sysrev.web.routes.api.core :refer
             [def-webapi web-api-routes web-api-routes-order]]
            [sysrev.config.core :refer [env]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? parse-integer]]))

;; weird bug in cider:
;; If you (run-tests) in sysrev.test.web.routes.api.handlers
;; then this file will no longer load with the message
;; "Unable to resolve spec: :sysrev.shared.spec.web-api/web"
;; you will need to load the namespace 'sysrev.web.routes.api.core'
;; to allow for this namespace to load

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
    (let [{:keys [email password]} (-> request
                                       :query-params
                                       walk/keywordize-keys)
          valid (users/valid-password? email password)
          user (when valid (users/get-user-by-email email))
          verified (users/primary-email-verified? (:user-id user))
          success (boolean valid)]
      (if success
        {:api-token (:api-token user)}
        (make-error-response 403 :api "User authentication failed")))))

;; TODO: don't return email addresses
;; TODO: change response format, include markdown description
(def-webapi
  :project-info :get
  {:required [:project-id]
   :project-role "member"}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (-> request :body)]
      (let [project (q/query-project-by-id project-id [:*])
            members (-> (select :u.user-id)
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
   :require-admin? true
   :check-answers? true
   :doc (->> ["\"pmids\": array of integer PubMed IDs"
              ""
              "Imports articles from PubMed."
              "On success, returns the project article count after completing import."]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [api-token project-id pmids] :as body}
          (-> request :body)]
      (cond
        (or (not (seqable? pmids))
            (empty? pmids)
            (not (every? integer? pmids)))
        (make-error-response
         500 :api "pmids must be an array of integers")
        :else
        (let [{:keys [error]} (import/import-pmid-vector
                               project-id {:pmids pmids} {:use-future? false})]
          (if error
            {:error {:message error}}
            {:result
             {:success true
              :attempted (count pmids)
              :project-articles
              (project/project-article-count project-id)}}))))))

(def-webapi
  :import-article-text :post
  {:required [:project-id :articles]
   :require-admin? true
   :check-answers? true
   :doc (->> ["articles: array of article maps"
              ""
              "Imports articles from manually provided values."
              ""
              "Required fields in article map:"
              "  primary-title: string"
              "  abstract: string"
              ""
              "Optional fields include:"
              "  authors: vector of strings"
              "  public-id: string (PMID value)"
              ""
              "On success, returns the project article count after completing import."]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [api-token project-id articles] :as body}
          (-> request :body)]
      (cond
        (or (not (seqable? articles))
            (empty? articles)
            (not (every? #(and (map? %)
                               (-> % :primary-title string?)
                               (-> % :abstract string?))
                         articles)))
        (make-error-response
         500 :api (str "articles must be an array of maps"
                       " with keys \"primary-title\" and \"abstract\""))
        :else
        (let [{:keys [error]} (import/import-article-text-manual
                               project-id {:articles articles} {:use-future? false})]
          (if error
            {:error {:message error}}
            {:result
             {:success true
              :attempted (count articles)
              :project-articles
              (project/project-article-count project-id)}}))))))

;; disabled, let users join on their own, use invite link
#_
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

;; disabled, too dangerous, can be done via web UI
#_
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

;; TODO: add a way to do this (or disable account) from web interface
#_
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

;; TODO: remove for now, use web interface
#_
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

;; TODO: needed? safe?
(def-webapi
  :create-project :post
  {:required [:project-name]
   #_ :require-admin? #_ true}
  (fn [request]
    (let [{:keys [api-token project-name add-self?]} (:body request)
          {:keys [user-id]} (users/get-user-by-api-token api-token)]
      (api/create-project-for-user! project-name user-id))))

;; TODO: does tom need this? disable for now
#_
(def-webapi
  :delete-project :post
  {:required [:project-id]
   :project-role "admin"
   :check-answers? true
   :doc "Deletes project and all database entries belonging to it."}
  (fn [request]
    (let [{:keys [project-id api-token] :as body}
          (-> request :body)
          {:keys [user-id]}
          (users/get-user-by-api-token api-token)]
      (api/delete-project! project-id user-id))))

;; TODO: allow public project access
(def-webapi
  :project-labels :get
  {:required [:project-id]
   :project-role "member"
   :doc "Returns a map of all label definitions for the project."}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (-> request :body)]
      {:result (project/project-labels project-id true)})))

;; TODO: disable for now, not needed, web UI
#_
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

;; TODO: enable if tom/anyone wants
#_
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
#_
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
#_
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

;; not used, predictions stored in db from this project directly
#_
(def-webapi
  :create-predict-run :post
  {:required [:project-id :predict-version-id]
   :project-role "admin"
   :doc
   "Creates a predict-run entry for a project, and returns a predict-run-id
  to use in later requests to reference the new entry.

  `prediction-version-id` is a required integer ID referring to an entry in
  `predict_version` table. This identifies the prediction algorithm used."}
  (fn [request]
    (let [{:keys [project-id predict-version-id] :as body}
          (-> request :body)]
      (assert (integer? project-id))
      (assert (integer? predict-version-id))
      (let [predict-run-id
            (predict/create-predict-run project-id predict-version-id)]
        {:result {:predict-run-id predict-run-id}}))))
#_
(def-webapi
  :store-article-predictions :post
  {:required [:project-id :predict-run-id :label-id :article-values]
   :project-role "admin"
   :doc
   "Creates label-predicts entries for `predict-run-id` in `project-id`,
  using the values from `article-values`.

  `predict-run-id` is an integer ID representing an individual run of a
  prediction algorithm on a project. A value can be obtained from the return
  value of the `create-predict-run` API call.

  `label-id` is a UUID string referring to the project label for which
  prediction values are being stored.

  `article-values` is a vector of entries that each contain a prediction value
  for a single article. Each entry should be a map containing two fields:
  `article-id` (integer) and `value` (float)."}
  (fn [request]
    (let [{:keys [project-id predict-run-id label-id article-values] :as body}
          (-> request :body)
          label-id (sutil/to-uuid label-id)]
      (assert (integer? project-id))
      (assert (integer? predict-run-id))
      (assert (uuid? label-id))
      (assert (or (empty? article-values)
                  (sequential? article-values)))
      (assert (every? map? article-values))
      (predict/store-article-predictions
       predict-run-id label-id article-values))))

(def-webapi
  :project-annotations :get
  {:require [:project-id]
   :require-token? false
   :allow-public true ;; FIX: not implemented, full anonymous access
   :doc
   "Returns a list of annotations for a project.

    json format:
    {result: [{
     selection: <string> // the selection of text being annotated,
     annotation: <string> // the annotation associated with a selection,
     semantic-class: <string> // the semantic class this annotation belongs to,
     article-id: <number> // the internal Sysrev id associated with the article,
     pmid: <number> // optional, the integer pmid value associated with the article, when available
     pdf-source: <string> // optional, the string description of the Sysrev url where a pdf can be found.}]}

    There are currently only two types of annotations for article, those which label an abstract or label a pdf. If an annotation has a pdf-source, it can be assumed the selection comes from a pdf. Otherwise, if there is no pdf-source the selection is associated with just the title, author or abstract of an article"}
  (fn [request]
    (let [{:keys [project-id] :as body}
          (walk/keywordize-keys (:query-params request))]
      {:result (api/project-annotations (parse-integer project-id))})))

(def-webapi
  :clone-project :post
  {:required [:project-id :new-project-name
              :articles :labels :members :answers]
   :optional [:admin-members-only :user-names-only :user-ids-only]
   :require-admin? true
   :doc (->> ["\"project-id\": Integer, source project to clone from"
              "\"new-project-name\": String, title for cloned project"
              "\"articles\": Boolean, whether to copy articles"
              "\"labels\": Boolean, whether to copy label definitions"
              "\"members\": Boolean, whether to copy project members"
              "\"answers\": Boolean, whether to copy user label answers"
              ""
              "\"admin-members-only\": Optional boolean, skip copying non-admin members"
              "\"user-names-only\": Optional array of strings, copy only members with display names contained in array"
              "\"user-ids-only\": Optional array of integers, copy only members with user-id contained in array"
              ""
              "On success, returns the newly created project-id."]
             (str/join "\n"))}
  (fn [request]
    (let [{:keys [api-token project-id new-project-name
                  articles labels answers members
                  admin-members-only user-names-only user-ids-only] :as body}
          (-> request :body)]
      (cond
        (not (string? new-project-name))
        (make-error-response
         500 :api "new-project-name: String value must be provided")

        (not (boolean? articles))
        (make-error-response
         500 :api "articles: Boolean value must be provided")

        (not (boolean? labels))
        (make-error-response
         500 :api "labels: Boolean value must be provided")

        (not (boolean? members))
        (make-error-response
         500 :api "members: Boolean value must be provided")

        (not (boolean? answers))
        (make-error-response
         500 :api "answers: Boolean value must be provided")

        (and answers (not members))
        (make-error-response
         500 :api "answers: Inconsistent option, members must be true")

        (not (or (nil? admin-members-only)
                 (boolean? admin-members-only)))
        (make-error-response
         500 :api "admin-members-only: Must be boolean")

        (not (or (nil? user-names-only)
                 (and (sequential? user-names-only)
                      (every? string? user-names-only))))
        (make-error-response
         500 :api "user-names-only: Must be array of strings")

        (not (or (nil? user-ids-only)
                 (and (sequential? user-ids-only)
                      (every? integer? user-ids-only))))
        (make-error-response
         500 :api "user-ids-only: Must be array of integers")

        (< 1 (count (->> [admin-members-only user-names-only user-ids-only]
                         (remove nil?))))
        (make-error-response
         500 :api "Only one of admin-members-only, user-names-only, user-ids-only may be used")

        :else
        (let [to-user-ids (fn [user-names]
                            ;; TODO: will need to update this for customizable user names
                            (->> (do-query (q/select-project-members
                                            project-id [:u.user-id :u.email]))
                                 (filter #(and (-> % :email string?)
                                               (in? user-names
                                                    (-> % :email (str/split #"@") first))))
                                 (map :user-id)))
              user-ids (or user-ids-only
                           (and user-names-only (to-user-ids user-names-only)))
              new-project-id (clone/clone-project new-project-name project-id
                                                  :articles articles
                                                  :labels labels
                                                  :answers answers
                                                  :members members
                                                  :user-ids-only user-ids)]
          {:result {:success true
                    :new-project {:project-id new-project-id
                                  :name new-project-name
                                  :url (str "https://sysrev.com/p/" new-project-id)}}})))))

;; Prevent Cider compile command from returning a huge def-webapi map
nil
