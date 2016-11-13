(ns sysrev.web.app
  (:require [compojure.core :refer :all]
            [compojure.route :refer [not-found]]
            [compojure.response :refer [Renderable]]
            [ring.util.response :as r]
            [clojure.string :as str]
            [clojure.stacktrace :refer [print-cause-trace]]
            [sysrev.web.index :as index]
            [sysrev.db.core :refer [*active-project*]]
            [sysrev.db.users :refer [get-user-by-id]]
            [sysrev.db.project :refer [project-member]]
            [sysrev.util :refer [in? integerify-map-keys]]))

(defn current-user-id [request]
  (-> request :session :identity :user-id))

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
    (binding [*active-project* (-> request :session :active-project)]
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
           500 :unknown "Unexpected error processing request" e))))))

(defmacro wrap-permissions
  "Wrap request handler body to check if user is authorized to perform the
  request. If authorized then runs body and returns result; if not authorized,
  returns an error without running body."
  [request required-perms & body]
  `(let [request# ~request
         required-perms# ~required-perms
         user-id# (current-user-id request#)
         member# (and user-id#
                      *active-project*
                      (project-member *active-project* user-id#))
         user# (and user-id# (get-user-by-id user-id#))
         member-perms# (:permissions member#)
         site-admin?# (in? (:permissions user#) "admin")
         body-fn# #(do ~@body)]
     (cond
       (not (integer? user-id#))
       {:error {:status 401
                :type :authentication
                :message "Not logged in"}}

       (empty? required-perms#)
       (body-fn#)
       
       (and (not (empty? required-perms#))
            (not (integer? *active-project*)))
       {:error {:status 403
                :type :project
                :message "No project selected"}}
       
       (nil? member#)
       {:error {:status 403
                :type :member
                :message "Not authorized (project)"}}

       (not (every? (in? member-perms#) required-perms#))
       {:error {:status 403
                :type :permissions
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
