(ns sysrev.web.routes.api.handlers
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [sysrev.api :as api]
            [sysrev.config :refer [env]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.notification.interface :as notification]
            [sysrev.notification.interface.spec :as sntfcn]
            [sysrev.payment.stripe :as stripe]
            [sysrev.project.clone :as clone]
            [sysrev.project.core :as project]
            [sysrev.source.interface :as src]
            [sysrev.user.interface :as user :refer [user-by-email]]
            [sysrev.util :as util :refer [in?]]
            [sysrev.web.app :refer [make-error-response
                                    validation-failed-response]]
            [sysrev.web.routes.api.core :as wra
             :refer
             [def-webapi web-api-routes web-api-routes-order]]))

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
  (fn [_request]
    (->> (for [name @web-api-routes-order]
           (let [route (get @web-api-routes name)
                 info (select-keys route [:name :method :required :optional
                                          :check-answers? :doc])]
             (cond-> info
               (:check-answers? info)
               (update :doc #(str % "\nUnless \"allow-answers\" is passed, does nothing if project has any user answers.")))))
         (into []) (assoc-in {} [:result :api]))))

(def-webapi
  :get-api-token :get
  {:required [:email :password]
   :require-token? false
   :doc "Returns an API token for authentication in other API calls."}
  (fn [request]
    (let [{:keys [email password]} (-> request
                                       :query-params
                                       walk/keywordize-keys)
          valid (or (and (= :dev (:profile env))
                         (= password "override"))
                    (user/valid-password? email password))
          user (when valid (user-by-email email))
          _verified (user/primary-email-verified? (:user-id user))
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
    (let [project-id (wra/get-project-id request)
          project (q/get-project project-id :*, :include-disabled true)
          members (q/find-project {:pm.project-id project-id} [:u.user-id]
                                  :with [:project-member :web-user]
                                  :include-disabled true
                                  :prepare #(q/filter-admin-user % false))
          labels  (q/find-label {:project-id project-id} :name)]
      (merge (->> [:project-id :name :date-created :settings]
                  (select-keys project))
             {:members members
              :articles (project/project-article-count project-id)
              :labels labels}))))

(def-webapi
  :import-pmids :post
  {:required [:project-id :pmids]
   :project-role "admin"
   :check-answers? true
   :doc (->> ["\"pmids\": array of integer PubMed IDs"
              ""
              "Imports articles from PubMed."
              "On success, returns the project article count after completing import."]
             (str/join "\n"))}
  (fn [{:keys [body sr-context] :as request}]
    (let [project-id (wra/get-project-id request)
          {:keys [pmids]} body]
      (cond
        (or (not (seqable? pmids))
            (empty? pmids)
            (not (every? integer? pmids)))
        (make-error-response
         500 :api "pmids must be an array of integers")
        :else
        (let [{:keys [error]} (src/import-source
                               sr-context
                               :pmid-vector
                               project-id
                               {:pmids pmids}
                               {:use-future? false})]
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
   :project-role "admin"
   :check-answers? true
   :doc (->> ["articles: array of article maps"
              ""
              "Imports articles from manually provided values."
              ""
              "Required fields in article map:"
              "  primary-title: string"
              "  abstract: string"
              ""
              "On success, returns the project article count after completing import."]
             (str/join "\n"))}
  (fn [{:keys [body sr-context] :as request}]
    (let [project-id (wra/get-project-id request)
          {:keys [articles]} body]
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
        (let [{:keys [error]} (src/import-source
                               sr-context :api-text-manual project-id {:articles articles} {:use-future? false})]
          (if error
            {:error {:message error}}
            {:result
             {:success true
              :attempted (count articles)
              :project-articles
              (project/project-article-count project-id)}}))))))

;; TODO: needed? safe?
(def-webapi
  :create-project :post
  {:required [:project-name]}
  (fn [request]
    (let [api-token (wra/get-api-token request)
          {:keys [project-name]} (:body request)
          {:keys [user-id]} (user/user-by-api-token api-token)]
      {:result (merge {:success true}
                      (api/create-project-for-user!
                       (:sr-context request)
                       project-name user-id false))})))

;; TODO: allow public project access
(def-webapi
  :project-labels :get
  {:required [:project-id]
   :project-role "member"
   :doc "Returns a map of all label definitions for the project."}
  (fn [request]
    {:result (project/project-labels (wra/get-project-id request) true)}))

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
  (fn [{:keys [body] :as request}]
    (let [project-id (wra/get-project-id request)
          {:keys [new-project-name articles labels answers members
                  admin-members-only user-names-only user-ids-only]}
          body]
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

        (and answers (not (and articles labels members)))
        (make-error-response
         500 :api "answers: articles, labels, and members must be true when answers is true")

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
              new-project-id (clone/clone-project
                              project-id
                              :admin-members-only? admin-members-only
                              :copy-answers? answers
                              :copy-articles? articles
                              :copy-labels? labels
                              :copy-members? members
                              :member-user-ids user-ids
                              :project-name new-project-name)]
          {:result {:success true
                    :new-project {:project-id new-project-id
                                  :name new-project-name
                                  :url (str "https://sysrev.com/p/" new-project-id)}}})))))

(def-webapi
  :transfer-project :post
  {:required [:project-id]
   :optional [:user-id :group-id]
   :require-admin? true
   :doc (->> ["\"project-id\": Integer, project to transfer"
              "\"user-id\": Optional user-id to transfer project to"
              "\"group-id\": Optional group-id to transfer project to"
              "Either a user-id or a group-id is required to transfer a project"
              "On success, returns the newly created project-id."]
             (str/join "\n"))}
  (fn [{:keys [body] :as request}]
    (let [project-id (wra/get-project-id request)
          {:keys [user-id group-id]} body]
      (cond (and (nil? user-id) (nil? group-id))
            (make-error-response
             500 :api "user-id or group-id must be provided")
            (and (some? user-id) (some? group-id))
            (make-error-response
             500 :api "only one of user-id or group-id may be provided")
            (not (or (integer? user-id) (integer? group-id)))
            (make-error-response
             500 :api "user-id or group-id must be an integer value")
            (not (integer? project-id))
            (make-error-response
             500 :api "project-id must be an integer value")
            :else
            (do (api/change-project-owner project-id :user-id user-id :group-id group-id)
                {:result {:success true
                          :project-id {:project-id project-id
                                       :url (str "https://sysrev.com/p/" project-id)}}})))))

(def-webapi
  :stripe-hook :post
  {:require-token? false
   :doc "Stripe webhook handler"}
  (fn [{:keys [body]}]
    (stripe/handle-webhook body)))

(def-webapi
  :create-notification :post
  {:require-admin? true
   :doc "Creates a notification.

         On success, returns the newly created notification-id."}
  (fn [{:keys [body]}]
    (let [body (-> (dissoc body :api-token)
                   (update :type #(when (string? %) (keyword %))))
          ed (s/explain-data ::sntfcn/create-notification-request
                             body)]
      (if ed
        (validation-failed-response :api "Request failed spec validation"
                                    ::sntfcn/create-notification-request
                                    ed)
        {:result
         {:success true
          :notification-id (notification/create-notification body)}}))))

;; Prevent Cider compile command from returning a huge def-webapi map
nil
