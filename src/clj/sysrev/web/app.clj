(ns sysrev.web.app
  (:require [compojure.core :refer :all]
            [compojure.route :refer [not-found]]
            [compojure.response :refer [Renderable]]
            [ring.util.response :as r]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [clojure.string :as str]
            [clojure.stacktrace :refer [print-cause-trace]]
            [sysrev.web.index :as index]
            [sysrev.db.users :refer [get-user-by-id get-user-by-api-token]]
            [sysrev.db.project :refer [project-member]]
            [sysrev.util :refer [parse-integer]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.resources :as res]))

(defn current-user-id [request]
  (or (-> request :session :identity :user-id)
      (when-let [api-token (-> request :body :api-token)]
        (:user-id (get-user-by-api-token api-token)))))

(defn active-project [request]
  (if-let [api-token (-> request :body :api-token)]
    (when (get-user-by-api-token api-token)
      (-> request :body :project-id))
    (-> request :params :project-id parse-integer)))

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

(defn wrap-no-cache [handler]
  #(some-> (handler %)
           (r/header "Cache-Control" "no-cache, no-store")))

(defn wrap-add-anti-forgery-token
  "Attach csrf token value to response if request did not contain it."
  [handler]
  #(let [response (handler %)]
     (let [req-csrf (get-in % [:headers "x-csrf-token"])
           csrf-match (and req-csrf (= req-csrf *anti-forgery-token*))]
       (if (and *anti-forgery-token*
                (map? (:body response))
                (not csrf-match))
         (assoc-in response [:body :csrf-token] *anti-forgery-token*)
         response))))

(defn wrap-sysrev-response [handler]
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
                  (and (seqable? body) (empty? body))
                  (make-error-response
                   500 :empty "Server error (no data returned)"
                   nil response)
                  :else response))
            session-meta (-> body meta :session)]
        ;; If the request handler attached a :session meta value to the result,
        ;; set that session value in the response.
        (merge
         (cond-> response
           session-meta (assoc :session session-meta)
           
           (and (map? body) res/build-id)
           (assoc-in [:body :build-id] res/build-id)
           
           (and (map? body) res/build-time)
           (assoc-in [:body :build-time] res/build-time))))
      (catch Throwable e
        (println "************************")
        (println (pr-str request))
        (print-cause-trace e)
        (println "************************")
        (make-error-response
         500 :unknown "Unexpected error processing request" e)))))

(defmacro wrap-permissions
  "Wrap request handler body to check if user is authorized to perform the
  request. If authorized then runs body and returns result; if not authorized,
  returns an error without running body."
  [request uperms-required mperms-required & body]
  (assert ((comp not empty?) body)
          "wrap-permissions: missing body form")
  `(let [request# ~request
         user-id# (current-user-id request#)
         project-id# (active-project request#)
         user# (and user-id# (get-user-by-id user-id#))
         member# (and user-id#
                      project-id#
                      (project-member project-id# user-id#))
         uperms# (:permissions user#)
         mperms# (:permissions member#)
         body-fn# #(do ~@body)]
     (cond
       (not (integer? user-id#))
       {:error {:status 401
                :type :authentication
                :message "Not logged in / Invalid API token"}}

       (not (every? (in? uperms#) ~uperms-required))
       {:error {:status 403
                :type :user
                :message "Not authorized"}}
       
       (and (empty? ~uperms-required)
            (empty? ~mperms-required))
       (body-fn#)
       
       (and (not (empty? ~mperms-required))
            (not (integer? project-id#)))
       {:error {:status 403
                :type :project
                :message "No project selected"}}
       
       (and (not (empty? ~mperms-required))
            (nil? member#))
       {:error {:status 403
                :type :member
                :message "Not authorized (project)"}}
       
       (and (not (every? (in? mperms#) ~mperms-required))
            (not (in? uperms# "admin")))
       {:error {:status 403
                :type :project
                :message "Not authorized"}}

       true
       (body-fn#))))

;; Overriding this to allow route handler functions to return result as
;; map value with the value being placed in response :body here.
(extend-protocol Renderable
  clojure.lang.APersistentMap
  (render [resp-map _]
    (merge (with-meta (r/response "") (meta resp-map))
           (if (contains? resp-map :body)
             resp-map
             {:body resp-map}))))
