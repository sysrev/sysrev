(ns sysrev.web.routes.api.core
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.web-api :as swa]
            [compojure.core :refer :all]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.web.app :refer
             [current-user-id active-project make-error-response]]
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

(defonce web-api-routes (atom {}))
(defonce web-api-routes-order (atom []))

;; TODO: handle anonymous read access to public projects.
;;       use :allow-public key
(defn def-webapi
  [name method opts handler]
  (let [opts (merge {:require-token? true} opts)
        opts (cond-> opts
               (:require-token? opts)
               (update :required #(vec (concat [:api-token] %)))
               (:check-answers? opts)
               (update :optional #(vec (concat [:allow-answers] %))))]
    (when (not (in? @web-api-routes-order name))
      (swap! web-api-routes-order #(vec (conj % name))))
    (swap! web-api-routes assoc name
           (merge {:name name :method method :handler handler} opts))))

(s/fdef def-webapi
        :args (s/cat :name ::swa/web
                     :method ::swa/method
                     :opts (s/keys :opt-un
                                   [::swa/required
                                    ::swa/optional
                                    ::swa/doc
                                    ::swa/require-token?
                                    ::swa/check-answers?
                                    ::swa/require-admin?
                                    ::swa/project-role])
                     :handler ::swa/handler))

(defn web-api-route [request]
  (when-let [route (some-> request :route-params :* keyword)]
    (get @web-api-routes route)))

(defn api-routes []
  (->>
   @web-api-routes-order
   (mapv
    (fn [name]
      (let [{:keys [name method handler]} (get @web-api-routes name)
            path (str "/web-api/" (clojure.core/name name))]
        (make-route method path handler))))
   (apply routes)))

(defn wrap-web-api-check-args [handler]
  (fn [request]
    (if-let [route (web-api-route request)]
      (let [required (:required route)
            args (cond (= (:request-method request)
                          :post)
                       (-> request :body keys)
                       (= (:request-method request)
                          :get)
                       (-> request :query-params walk/keywordize-keys keys))
            request-method (:request-method request)
            missing (->> required (remove (in? args)))]
        (if-not (empty? missing)
          (make-error-response
           500 :api
           (format "Missing arguments: %s"
                   (->> missing (mapv str) pr-str)))
          (handler request)))
      (handler request))))

(defn wrap-check-project-id [handler]
  (fn [request]
    (let [route (web-api-route request)]
      (if (and route (in? (:required route) :project-id))
        (let [project-id (-> request :body :project-id)
              project (and project-id (q/query-project-by-id
                                       project-id [:*]))]
          (cond
            (nil? project-id)
            (make-error-response
             403 :api "No project-id given")
            (nil? project)
            (make-error-response
             403 :api (format "No project with id=%s" project-id))
            :else (handler request)))
        (handler request)))))

(defn wrap-web-api-auth [handler]
  (fn [request]
    (if-let [route (web-api-route request)]
      (let [{:keys [require-token? require-admin? project-role required]} route
            {:keys [api-token project-id]} (-> request :body)
            user (and api-token (users/get-user-by-api-token api-token))
            admin? (and user (in? (:permissions user) "admin"))
            member-roles
            (and user project-id
                 (-> (q/select-project-members project-id [:m.permissions])
                     (merge-where [:= :u.user-id (:user-id user)])
                     (->> do-query first :permissions)))]
        (cond
          (and require-token? (nil? user))
          (make-error-response
           403 :api "Invalid API token")
          (and require-admin? (not admin?))
          (make-error-response
           403 :api "Query requires user admin permissions")
          (and project-role
               (not (in? member-roles project-role))
               (not admin?))
          (make-error-response
           403 :api (format "Query requires project role: %s"
                            (pr-str project-role)))
          :else (handler request)))
      (handler request))))

(defn wrap-check-allow-answers [handler]
  (fn [request]
    (let [route (web-api-route request)]
      (if (and route (:check-answers? route))
        (let [allow-answers (-> request :body :allow-answers)
              project-id (-> request :body :project-id)
              project (and project-id (q/query-project-by-id project-id [:*]))
              answers-count (and project-id project
                                 (-> (q/select-project-article-labels
                                      project-id nil [:%count.*])
                                     do-query first :count))]
          (cond
            (nil? answers-count)
            (make-error-response
             500 :api "wrap-check-allow-answers failed")
            (and (not (true? allow-answers))
                 (not= 0 answers-count))
            (make-error-response
             403 :api
             (->> [(format "Project name: \"%s\"" (:name project))
                   (format "Project contains %d entries of user answers."
                           answers-count)
                   "To confirm this is intended, include argument \"allow-answers\" with true value in your request."]
                  (str/join "\n")))
            :else (handler request)))
        (handler request)))))

(defn wrap-web-api [handler]
  (-> handler
      wrap-check-allow-answers
      wrap-web-api-auth
      wrap-check-project-id
      wrap-web-api-check-args))

;; HTTP client functions for testing API handlers
(defn webapi-request [method route body & {:keys [host port url]}]
  (let [port (or port (-> env :server :port))
        host (or host "localhost")
        base-request {:url (if url
                             (format "%sweb-api/%s" url route)
                             (format "http://%s:%d/web-api/%s"
                                     host port route))
                      :method method
                      :headers {"Content-Type" "application/json"}}
        request-map (condp = method
                      :get (conj base-request {:query-params body})
                      :post (conj base-request {:body (json/write-str body)}))
        {:keys [body]} @(client/request request-map)]
    (try (json/read-str body :key-fn keyword)
         (catch Throwable e body))))

(defn webapi-get [route body & opts]
  (apply webapi-request :get route body opts))
(defn webapi-post [route body & opts]
  (apply webapi-request :post route body opts))

