(ns sysrev.web.app
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.response :refer [Renderable]]
            [ring.util.response :as r]
            [clojure.string :as str]
            [clojure.stacktrace :refer [print-cause-trace]]
            [sysrev.web.index :as index]
            [sysrev.web.ajax :as ajax]
            [sysrev.web.auth :as auth]
            [sysrev.db.articles :as articles]
            [sysrev.db.documents :as docs]
            [sysrev.util :refer [parse-number]]))

(defn make-error-response
  [http-code etype emessage & [exception response]]
  (cond-> response
    true (assoc :status http-code
                :body {:error {:type etype
                               :message emessage}})
    exception (assoc-in [:body :error :exception] (str exception))))

(defn not-found-response [request]
  (-> (r/response (index/not-found request))
      (r/status 404)
      (r/header "Content-Type" "text/html; charset=utf-8")
      (cond-> (= (:request-method request) :head) (assoc :body nil))))

(defn not-authenticated-response [request]
  (make-error-response
   401 :authentication "Not logged in"))

(defn not-authorized-response [request]
  (make-error-response
   403 :permissions "Not authorized to perform request"))

(defn wrap-sysrev-api [handler]
  (fn [request]
    (try
      (let [{{{:keys [status type message exception]
               :or {status 500
                    type :api
                    message "Error processing request"}
               :as error} :error
              result :result :as body} :body
             :as response} (handler request)
            response
            (cond->
                (cond
                  ;; Return error if body has :error field
                  error (do (when exception
                              (println "************************")
                              (println (pr-str request))
                              (print-cause-trace exception)
                              (println "************************"))
                            (make-error-response
                             status type message exception response))
                  ;; Otherwise return result if body has :result field
                  result response
                  ;; If no :error or :result key, wrap the value in :result
                  (map? body) (update response :body #(hash-map :result %))
                  ;;
                  (empty? body) (make-error-response
                                 500 :empty "Server error (no data returned)"
                                 nil response)
                  :else response))
            session-meta (-> body meta :session)]
        ;; If the request handler attached a :session meta value to the result,
        ;; set that session value in the response.
        (cond-> response
          session-meta (assoc :session session-meta)))
      (catch Throwable e
        (println "************************")
        (println (pr-str request))
        (print-cause-trace e)
        (println "************************")
        (make-error-response
         500 :unknown "Unexpected error processing request" e)))))

;; Overriding this to allow route handler functions to return results as
;; map values with the value being placed in response :body here.
(extend-protocol Renderable
  clojure.lang.APersistentMap
  (render [resp-map _]
    (merge (with-meta (r/response "") (meta resp-map))
           (if (contains? resp-map :body)
             resp-map
             {:body resp-map}))))

(defroutes app-routes
  (GET "/" [] index/index)
  (POST "/api/auth/login" request
        (auth/web-login-handler request))
  (POST "/api/auth/logout" request
        (auth/web-logout-handler request))
  (POST "/api/auth/register" request
        (auth/web-create-account-handler request))
  (GET "/api/auth/identity" request
       (auth/web-get-identity request))
  (GET "/api/criteria" request
       (ajax/web-criteria))
  (GET "/api/all-projects" []
       (ajax/web-all-projects))
  (GET "/api/article-documents" []
       (docs/all-article-document-paths))
  (GET "/api/project-info" []
       (ajax/web-project-summary))
  (GET "/api/user-info/:user-id" request
       (let [request-user-id (ajax/current-user-id request)
             query-user-id (-> request :params :user-id Integer/parseInt)]
         (ajax/web-user-info
          query-user-id (= request-user-id query-user-id))))
  (GET "/api/label-task/:interval" request
       (let [user-id (ajax/current-user-id request)
             interval (-> request :params :interval Integer/parseInt)
             above-score (-> request :params :above-score)
             above-score (when above-score (Double/parseDouble above-score))]
         (ajax/web-label-task user-id interval above-score)))
  (GET "/api/article-info/:article-id" [article-id]
       (let [article-id (Integer/parseInt article-id)]
         (ajax/web-article-info article-id)))
  (POST "/api/set-labels" request
        (ajax/web-set-labels request false))
  (POST "/api/confirm-labels" request
        (ajax/web-set-labels request true))
  ;; Match
  (GET "*" {:keys [uri] :as request}
       (if (-> uri (str/split #"/") last (str/index-of \.))
         ;; Fail if request appears to be for a static file
         (not-found-response request)
         ;; Otherwise serve index.html
         (index/index request)))
  (route/not-found (index/not-found nil)))
