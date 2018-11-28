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
            [sysrev.db.project :as project]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [in? parse-integer]]
            [sysrev.resources :as res]))

(defn current-user-id [request]
  (or (-> request :session :identity :user-id)
      (when-let [api-token (and (-> request :body map?)
                                (-> request :body :api-token))]
        (:user-id (get-user-by-api-token api-token)))))

(defn active-project [request]
  (if-let [api-token (and (-> request :body map?)
                          (-> request :body :api-token))]
    (when (get-user-by-api-token api-token)
      (-> request :body :project-id))
    (let [project-id (or (-> request :params :project-id)
                         (and (-> request :body map?)
                              (-> request :body :project-id))
                         #_ (-> request :session :identity :default-project-id))]
      (cond
        (integer? project-id) project-id
        (string? project-id)  (parse-integer project-id)
        :else                 nil))))

(defn make-error-response
  [http-code etype emessage & [exception response]]
  (cond-> response
    true (assoc :status http-code
                :body {:error {:type etype
                               :message emessage}})
    #_ exception false (assoc-in [:body :error :exception] (str exception))))

(defn not-found-response [request]
  (-> (r/response (index/not-found request))
      (r/status 404)
      (r/header "Content-Type" "text/html; charset=utf-8")
      (cond-> (= (:request-method request) :head) (assoc :body nil))))

(defn wrap-no-cache [handler]
  #(some-> (handler %)
           (r/header "Cache-Control" "no-cache, must-revalidate")))

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
                  ;; If handler gave HTTP redirect response, return it
                  (= (:status response) 302) response
                  ;;
                  (and (seqable? body) (empty? body))
                  (make-error-response
                   500 :empty "Server error (no data returned)"
                   nil response)
                  :else response))
            session-meta (or (-> body meta :session)
                             (-> response meta :session))]
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

(defmacro wrap-authorize
  "Wrap request handler body to check if user is authorized to perform the
  request. If authorized then runs body and returns result; if not authorized,
  returns an error without running body.

  Set `logged-in` as true to fail unless logged in.

  Set `developer` as true to fail unless logged in as admin (dev) user.

  If `roles` is set, the value should be a vector of member permission strings,
  and the user is required to (1) be a member of the project and (2) have at
  least one of the listed member permissions for the project. When set, this
  implies a value of `true` for `logged-in`.

  Set `allow-public` as true to permit access always if the referenced project
  is configured as public-readable. If the project is not public-readable, the
  logic will be equivalent to
  `{:roles [\"member\"] :logged-in true}`.
  Because `roles` and `logged-in` conditions are encompassed by this setting,
  an error will be thrown at compile time if either is passed in alongside
  `allow-public`.

  `authorize-fn` should be nil or a one-argument function which takes the Ring
  request map and returns a boolean \"authorized\" value, and will be tested as
  an additional custom condition after the other conditions."
  [request
   {:keys [logged-in developer roles allow-public authorize-fn]
    :or {logged-in nil
         developer false
         allow-public false
         roles nil
         authorize-fn nil}
    :as conditions}
   & body]
  (assert ((comp not empty?) body)
          "wrap-authorize: missing body form")
  (assert (not (and (contains? conditions :allow-public)
                    (or (contains? conditions :logged-in)
                        (contains? conditions :roles))))
          (str "wrap-authorize: `logged-in` and `roles` are not"
               " allowed when `allow-public` is set"))
  `(let [ ;; macro parameter gensyms
         request# ~request
         logged-in# ~logged-in
         developer# ~developer
         allow-public# ~allow-public
         roles# ~roles
         authorize-fn# ~authorize-fn
         body-fn# #(do ~@body)

         ;; set implied condition values
         logged-in# (if (or (not-empty roles#)
                            (true? developer#))
                      true logged-in#)
         roles# (if allow-public# ["member"] roles#)

         user-id# (current-user-id request#)
         project-id# (active-project request#)
         valid-project# (and (integer? project-id#)
                             (project/project-exists? project-id#))
         public-project# (and valid-project#
                              (-> (project/project-settings project-id#)
                                  :public-access true?))
         user# (and user-id# (get-user-by-id user-id#))
         member# (and user-id#
                      valid-project#
                      (project/project-member project-id# user-id#))
         dev-user?# (in? (:permissions user#) "admin")
         mperms# (:permissions member#)]
     (cond
       (and project-id# (not valid-project#))
       {:error {:status 404
                :type :not-found
                :message (format "Project (%s) not found" project-id#)}}

       (and (not (integer? user-id#))
            (or logged-in#
                (and allow-public# valid-project# (not public-project#))))
       {:error {:status 401
                :type :authentication
                :message "Not logged in / Invalid API token"}}

       (and developer# (not dev-user?#))
       {:error {:status 403
                :type :user
                :message "Not authorized (developer function)"}}

       ;; route definition and project settings both allow public access
       (and allow-public# valid-project# public-project#)
       (body-fn#)

       (and (not-empty roles#)
            (not (integer? project-id#)))
       {:error {:status 403
                :type :project
                :message "No project selected"}}

       (and
        ;; member role requirements are set
        (not-empty roles#)
        ;; current member does not have any of the permitted roles
        (not (some (in? mperms#) roles#))
        ;; allow dev users to ignore project role conditions
        (not dev-user?#))
       {:error {:status 403
                :type :project
                :message "Not authorized (project member)"}}

       (and ((comp not nil?) authorize-fn#)
            (false? (authorize-fn# request#)))
       {:error {:status 403
                :type :project
                :message "Not authorized (authorize-fn)"}}

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
