(ns sysrev.web.app
  (:require [clojure.string :as str]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.tools.logging :as log]
            [compojure.core :refer :all]
            [compojure.route :refer [not-found]]
            [compojure.response :refer [Renderable]]
            [ring.util.request :as request]
            [ring.util.response :as r]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [sysrev.api :as api]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users :refer
             [get-user user-by-api-token update-member-access-time]]
            [sysrev.project.core :as project]
            [sysrev.resources :as res]
            [sysrev.web.index :as index]
            [sysrev.stacktrace :refer [print-cause-trace-custom]]
            [sysrev.util :as util :refer [pp-str]]
            [sysrev.shared.util :refer [in? parse-integer ensure-pred]]))

(defn current-user-id [request]
  (or (-> request :session :identity :user-id)
      (when-let [api-token (and (-> request :body map?)
                                (-> request :body :api-token))]
        (:user-id (user-by-api-token api-token)))))

(defn active-project [request]
  (if-let [api-token (and (-> request :body map?)
                          (-> request :body :api-token))]
    (when (user-by-api-token api-token)
      (-> request :body :project-id))
    (let [project-id (or (-> request :params :project-id)
                         (and (-> request :body map?)
                              (-> request :body :project-id)))]
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

(defn file-download-response [data filename content-type]
  (-> (r/response data)
      (r/header "Content-Type" content-type)
      (r/header "Content-Disposition"
                (format "attachment; filename=\"%s\"" filename))))

(defn csv-file-response [data filename]
  (file-download-response data filename "text/csv; charset=utf-8"))

(defn xml-file-response [data filename]
  (file-download-response data filename "text/xml; charset=utf-8"))

(defn text-file-response [data filename]
  (file-download-response data filename "text/plain; charset=utf-8"))

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

(defn wrap-log-request [handler]
  (if (and (contains? #{:test :remote-test} (:profile env))
           (contains? #{1 "1" true "true"} (:sysrev-log-requests env)))
    (fn [request]
      (util/with-print-time-elapsed
        (str (-> (:request-method request) name str/upper-case) " "
             (:uri request)
             (when (:query-string request) "?[...]"))
        (handler request)))
    (fn [request]
      (handler request))))


(defn- merge-default-success-true
  "If response result is a map and has keyword key values, merge in
  default {:success true} entry."
  [{:keys [body] :as response}]
  (let [{:keys [result]} (ensure-pred map? body)]
    (if (and (map? result)
             (some keyword? (keys result))
             (not (contains? result :success)))
      (update response :body assoc-in [:result :success] true)
      response)))

(defn log-request-exception [request e]
  (try (log/error "Request:\n" (pp-str request)
                  "Exception:\n" (with-out-str (print-cause-trace-custom e)))
       (catch Throwable e2
         (log/error "error in log-request-exception"))))

(defn request-client-ip [request]
  (or (get-in request [:headers "x-real-ip"])
      (:remote-addr request)))

(defn request-url [request]
  (some-> (:uri request) (str/split #"\?") first))

(defn log-web-event [{:keys [event-type logged-in user-id project-id skey client-ip
                             browser-url request-url request-method is-error meta]
                      :as event}]
  (letfn [(create-event []
            (let [;; avoid SQL exceptions from missing referenced entries
                  event (cond-> event
                          user-id (assoc :user-id
                                         (q/find-one :web-user {:user-id user-id} :user-id))
                          project-id (assoc :project-id
                                            (q/find-one :project {:project-id project-id}
                                                        :project-id)))]
              (try (q/create :web-event event)
                   (catch Throwable e (log/warn "log-web-event failed" #_ (.getMessage e))))))]
    (if db/*conn*
      (create-event)
      (future (db/with-transaction (create-event))))))

(defn make-web-request-event [request & {:keys [error exception]}]
  {:event-type "ajax"
   :logged-in (boolean (current-user-id request))
   :user-id (current-user-id request)
   :project-id (active-project request)
   :skey (:session/key request)
   :client-ip (request-client-ip request)
   :browser-url (get-in request [:headers "referer"])
   :request-url (request-url request)
   :request-method (some-> (:request-method request) name str/lower-case)
   :is-error (boolean (or error exception))
   :meta (cond error
               (db/to-jsonb
                {:error (merge error
                               (when (:exception error)
                                 {:stacktrace (with-out-str
                                                (-> (:exception error)
                                                    (print-cause-trace-custom)))}))})
               exception
               (db/to-jsonb
                {:error {:message (.getMessage exception)
                         :stacktrace (with-out-str
                                       (print-cause-trace-custom exception))}}))})

(defn make-web-page-event [request browser-url]
  {:event-type "page"
   :logged-in (boolean (current-user-id request))
   :user-id (current-user-id request)
   :project-id (active-project request)
   :skey (:session/key request)
   :client-ip (request-client-ip request)
   :browser-url browser-url})

(defn wrap-sysrev-response [handler]
  (fn [request]
    (try
      (let [{{{:keys [status type message exception]
               :or {status 500, type :api, message "Error processing request"}
               :as error} :error
              result :result :as body} :body
             :as response} (handler request)
            response
            (cond
              ;; Return error if body has :error field
              error (do (when exception (log-request-exception request exception))
                        (make-error-response status type message exception response))
              ;; Otherwise return result if body has :result field
              result (merge-default-success-true response)
              ;; If no :error or :result key, wrap the value in :result
              (map? body) (-> (update response :body #(hash-map :result %))
                              (merge-default-success-true))
              ;; If handler gave HTTP redirect response, return it
              (= 302 (:status response)) response
              ;; Return error on empty response body
              (and (seqable? body)
                   (empty? body)) (make-error-response
                                   500 :empty "Server error (no data returned)"
                                   nil response)
              ;; Otherwise return response unchanged
              :else response)
            session-meta (or (-> body meta :session)
                             (-> response meta :session))]
        (log-web-event (make-web-request-event request :error error))
        (cond-> response
          ;; If the request handler attached a :session meta value to
          ;; the result, set that session value in the response.
          session-meta                      (assoc :session session-meta)
          ;; Attach :build-id and :build-time fields to all response maps
          (and (map? body) res/build-id)    (assoc-in [:body :build-id] res/build-id)
          (and (map? body) res/build-time)  (assoc-in [:body :build-time] res/build-time)))
      (catch Throwable e
        (log-web-event (make-web-request-event request :exception e))
        (log-request-exception request e)
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
   {:keys [logged-in developer roles allow-public authorize-fn bypass-subscription-lapsed?]
    :or {logged-in nil
         developer false
         allow-public false
         roles nil
         authorize-fn nil
         bypass-subscription-lapsed? false}
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
         bypass-subscription-lapsed?# ~bypass-subscription-lapsed?
         ;; set implied condition values
         logged-in# (if (or (not-empty roles#) (true? developer#))
                      true logged-in#)
         roles# (if allow-public# ["member"] roles#)

         user-id# (current-user-id request#)
         project-id# (active-project request#)
         valid-project# (some-> project-id# ((ensure-pred integer?)) project/project-exists?)
         public-project# (and valid-project# (-> (project/project-settings project-id#)
                                                 ((comp true? :public-access))))
         user# (and user-id# (get-user user-id#))
         member# (and user-id# valid-project# (project/project-member project-id# user-id#))
         dev-user?# (in? (:permissions user#) "admin")
         mperms# (:permissions member#)

         record-access# #(when (and project-id# user# member#)
                           (future (try (update-member-access-time user-id# project-id#)
                                        (catch Throwable e#
                                          (log/warn "error updating access time:"
                                                    {:user-id user-id#})))))
         body-fn# #(do (record-access#) ~@body)]
     (cond (and project-id# (not valid-project#))
           {:error {:status 404 :type :not-found
                    :message (format "Project (%s) not found" project-id#)}}

           (and (not (integer? user-id#))
                (or logged-in# (and allow-public# valid-project# (not public-project#))))
           {:error {:status 401 :type :authentication
                    :message "Not logged in / Invalid API token"}}

           (and developer# (not dev-user?#))
           {:error {:status 403 :type :user
                    :message "Not authorized (developer function)"}}

           ;; route definition and project settings both allow public access
           (and allow-public# valid-project# public-project#)
           (body-fn#)

           (and (not-empty roles#) (not (integer? project-id#)))
           {:error {:status 403 :type :project
                    :message "No project selected"}}

           (and (not-empty roles#) ; member role requirements are set
                (not (some (in? mperms#) roles#)) ; member doesn't have any permitted role
                (not dev-user?#)) ; allow dev users to ignore project role conditions
           {:error {:status 403 :type :project
                    :message "Not authorized (project member)"}}

           (and ((comp not nil?) authorize-fn#)
                (false? (authorize-fn# request#)))
           {:error {:status 403 :type :project
                    :message "Not authorized (authorize-fn)"}}

           (and (not bypass-subscription-lapsed?#)
                (api/subscription-lapsed? project-id#)
                (not dev-user?#))
           {:error {:status 402 :type :project
                    :message "This action requires an upgraded plan"}}

           :else (body-fn#))))

;; Overriding this to allow route handler functions to return result as
;; map value with the value being placed in response :body here.
(extend-protocol Renderable
  clojure.lang.APersistentMap
  (render [resp-map _]
    (merge (with-meta (r/response "") (meta resp-map))
           (if (contains? resp-map :body)
             resp-map
             {:body resp-map}))))
