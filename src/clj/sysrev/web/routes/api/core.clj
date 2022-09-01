(ns sysrev.web.routes.api.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [compojure.core :as c]
            [honeysql.helpers :as sqlh :refer [merge-where]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.user.interface :refer [user-by-api-token]]
            [sysrev.web.app :refer [make-error-response]]
            [sysrev.shared.spec.web-api :as swa]
            [sysrev.util :refer [in?]]))

(defonce web-api-routes (atom {}))
(defonce web-api-routes-order (atom []))

(defn-spec def-webapi any?
  [name ::swa/name
   method ::swa/method
   opts (s/keys :opt-un [::swa/required ::swa/optional ::swa/doc ::swa/require-token?
                         ::swa/allow-public?
                         ::swa/check-answers? ::swa/require-admin? ::swa/project-role])
   handler ::swa/handler]
  (let [opts (merge {:require-token? (not (:allow-public? opts))} opts)
        opts (cond-> opts
               (:require-token? opts)
               (update :required #(vec (concat [:api-token] %)))
               (:check-answers? opts)
               (update :optional #(vec (concat [:allow-answers] %))))]
    (when (not (in? @web-api-routes-order name))
      (swap! web-api-routes-order #(vec (conj % name))))
    (swap! web-api-routes assoc name
           (merge {:name name :method method :handler handler} opts))))

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
        (c/make-route method path handler))))
   (apply c/routes)))

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
            ;; request-method (:request-method request)
            missing (->> required (remove (in? args)))]
        (if (seq missing)
          (make-error-response 500 :api (format "Missing arguments: %s"
                                                (->> missing (mapv str) pr-str)))
          (handler request)))
      (handler request))))

(defn wrap-check-project-id [handler]
  (fn [{:keys [body params request-method] :as request}]
    (let [route (web-api-route request)]
      (if (and route (in? (:required route) :project-id))
        (let [project-id (or (:project-id body)
                             (when (= :get request-method)
                               (some-> params :project-id parse-long)))
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
      (let [{:keys [require-token? require-admin? project-role #_ required]} route
            {:keys [api-token project-id]} (-> request :body)
            user (and api-token (user-by-api-token api-token))
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
