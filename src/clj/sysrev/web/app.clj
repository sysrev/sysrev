(ns sysrev.web.app
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [compojure.response :refer [Renderable]]
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
   [ring.util.response :as r]
   [sysrev.config :refer [env]]
   [sysrev.db.core :as db]
   [sysrev.postgres.interface :as pg]
   [sysrev.project.core :as project]
   [sysrev.project.member :refer [project-member]]
   [sysrev.project.plan :as pplan]
   [sysrev.slack :as slack]
   [sysrev.stacktrace :refer [print-cause-trace-custom]]
   [sysrev.user.interface :as user :refer [user-by-api-token]]
   [sysrev.util :as util :refer [in? parse-integer when-test]]
   [sysrev.web.build :as build]
   [sysrev.web.index :as index]))

(defn current-user-id [request]
  (or (when-let [api-token (and (-> request :body map?)
                                (-> request :body :api-token))]
        (:user-id (user-by-api-token api-token)))
      (-> request :session :identity :user-id)))

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
  (let [dev-or-test (#{:dev :test} (:profile env))
        cause-trace (some-> exception print-cause-trace-custom with-out-str)]
    (-> response
        (assoc :status http-code)
        (update-in [:body :error] assoc :type etype :message emessage)
        (cond->
         exception
         (assoc-in [:body :error :exception] (str exception))
         (and exception dev-or-test)
         (assoc-in [:body :error :stacktrace] cause-trace)))))

(defn validation-failed-response [etype emessage spec explain-data]
  {:status 500
   :body {:error {:type etype :message emessage}
          :explain-data explain-data
          :spec spec}})

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
  #(let [response (handler %)
         req-csrf (get-in % [:headers "x-csrf-token"])
         csrf-match (and req-csrf (= req-csrf *anti-forgery-token*))]
     (if (and *anti-forgery-token*
              (map? (:body response))
              (not csrf-match))
       (assoc-in response [:body :csrf-token] *anti-forgery-token*)
       response)))

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

(defn wrap-robot-noindex [handler]
  (fn [request]
    (let [resp (handler request)
          headers (:headers resp)]
      (assoc resp :headers
             (assoc headers "X-Robots-Tag" "noindex, nofollow")))))

(defn- merge-default-success-true
  "If response result is a map and has keyword key values, merge in
  default {:success true} entry."
  [{:keys [body] :as response}]
  (let [{:keys [result]} (when-test map? body)]
    (if (and (map? result)
             (some keyword? (keys result))
             (not (contains? result :success)))
      (update response :body assoc-in [:result :success] true)
      response)))

(defn request-client-ip [request]
  (or (get-in request [:headers "x-real-ip"])
      (:remote-addr request)))

(defn request-url [request]
  (some-> (:uri request) (str/split #"\?") first))

(defn log-web-event [sr-context event]
  (when (not= :test (:profile env))
    (db/with-long-tx [sr-context sr-context]
      (->> {:insert-into :web-event :values [event]}
           (db/execute-one! sr-context)))))

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
               (pg/jsonb-pgobject
                {:error (merge error
                               (when (:exception error)
                                 {:stacktrace (with-out-str
                                                (-> (:exception error)
                                                    (print-cause-trace-custom)))}))})
               exception
               (pg/jsonb-pgobject
                {:error {:message (.getMessage ^Throwable exception)
                         :stacktrace (with-out-str
                                       (print-cause-trace-custom exception))}}))})

(defn wrap-sysrev-response [handler]
  (fn [{:as request :keys [sr-context]}]
    (try
      (let [{{{:keys [status type message exception]
               :or {status 500, type :api, message "Error processing request"}
               :as error} :error
              result :result} :body
             :keys [headers]
             :as response} (handler request)
            body (:body response)
            response
            (cond
              ;; Return error if body has :error field
              error (let [response (if (and (= #{:dev :test} (:profile env))
                                            (ex-data exception))
                                     (assoc response :ex-data exception)
                                     response)]
                      (when exception (slack/log-request-exception request exception))
                      (-> (make-error-response status type message exception response)
                          (assoc-in [:body :error :uri] (:uri request))))
              ;; Otherwise return result if body has :result field
              result (merge-default-success-true response)
              ;; return raw body if it is in json-lines format
              (= "application/ndjson" (get headers "Content-Type")) response
              ;; If no :error or :result key, wrap the value in :result
              (map? body) (-> (update response :body #(hash-map :result %))
                              (merge-default-success-true))
              ;; If handler gave HTTP redirect response, return it
              (= 302 (:status response)) response
              ;; Return error on empty response body
              (and (seqable? body)
                   (empty? body)) (do (slack/log-request-exception
                                       request
                                       (Exception. "Server error (no data returned)"))
                                      (make-error-response
                                       500 :empty "Server error (no data returned)"
                                       nil response))
              ;; Otherwise return response unchanged
              :else response)
            session-meta (or (-> body meta :session)
                             (-> response meta :session))]
        (log-web-event sr-context (make-web-request-event request :error error))
        (cond-> response
          ;; If the request handler attached a :session meta value to
          ;; the result, set that session value in the response.
          session-meta                        (assoc :session session-meta)
          ;; Attach :build-id and :build-time fields to all response maps
          (and (map? body) build/build-id)    (assoc-in [:body :build-id] build/build-id)
          (and (map? body) build/build-time)  (assoc-in [:body :build-time] build/build-time)))
      (catch Throwable e
        (log-web-event sr-context (make-web-request-event request :exception e))
        (slack/log-request-exception request e)
        (make-error-response
         500 :unknown "Unexpected error processing request" e)))))

(defn wrap-dynamic-vars
  "Bind dynamic vars to the appropriate values from the Postgres record."
  [handler {:keys [config postgres]}]
  {:pre [(fn? handler) (map? config) (map? postgres)]}
  (fn [request]
    (binding [db/*active-db* (atom postgres)
              db/*conn* nil
              db/*query-cache* (:query-cache postgres)
              db/*query-cache-enabled* (:query-cache-enabled postgres)
              db/*transaction-query-cache* nil
              env (merge env config)]
      (handler request))))

(defn wrap-sr-context
  "Add the Sysrev context map to the request."
  [handler sr-context]
  {:pre [(fn? handler) (map? sr-context)]}
  (fn [request]
    (handler (assoc request :sr-context sr-context))))

(defn wrap-sr-context-request
  "Add the request to the Sysrev context map."
  [handler]
  (fn [request]
    (handler (assoc-in request [:sr-context :request] request))))

(defn wrap-timeout
  "Force a timeout for requests.

   This relies on the use of JVM operations that check their interrupted
   status regularly."
  [handler timeout-ms]
  (fn [{:as request :keys [uri]}]
    (let [fut (future (handler request))
          response (deref fut timeout-ms ::timeout)]
      (if (not= ::timeout response)
        response
        (let [cancelled (future-cancel fut)]
          (log/warn (str "Request cancelled due to timeout after " timeout-ms " ms") uri)
          (cond
            (and cancelled (future-done? fut))
            (try
              @fut
              (catch Throwable e
                (throw (ex-info (str "Request cancelled due to timeout after " timeout-ms " ms")
                                {:cause e
                                 :request (dissoc request :sr-context)
                                 :timeout-ms timeout-ms}))))

            (future-done? fut)
            @fut

            :else
            (throw (ex-info (str "Failed to cancel request due to timeout after " timeout-ms " ms")
                            {:request (dissoc request :sr-context)
                             :timeout-ms timeout-ms}))))))))

(defn authorization-error
  "Checks if user is authorized to perform the
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
    :as conditions}]
  (assert (not (and (contains? conditions :allow-public)
                    (or (contains? conditions :logged-in)
                        (contains? conditions :roles))))
          (str "authorized?: `logged-in` and `roles` are not"
               " allowed when `allow-public` is set"))
  (let [;; set implied condition values
        logged-in (if (or (not-empty roles) (true? developer))
                    true
                    logged-in)
        roles (if allow-public ["member"] roles)
        user-id (current-user-id request)
        project-id (active-project request)
        valid-project (some-> project-id ((when-test integer?)) project/project-exists?)
        public-project (and valid-project (-> (project/project-settings project-id)
                                              ((comp true? :public-access))))
        member (and user-id valid-project (project-member project-id user-id))
        dev-user? (delay (user/dev-user? (:sr-context request) user-id))
        mperms (:permissions member)]
    (cond (and project-id (not valid-project))
          {:error {:status 404 :type :not-found
                   :message (format "Project (%s) not found" project-id)}}

          (and (not (integer? user-id))
               (or logged-in (and allow-public valid-project (not public-project))))
          {:error {:status 401 :type :authentication
                   :message "Not logged in / Invalid API token"}}

          (and developer (not @dev-user?))
          {:error {:status 403 :type :user
                   :message "Not authorized (developer function)"}}

           ;; route definition and project settings both allow public access
          (and allow-public valid-project public-project)
          nil

          (and (not-empty roles) (not (integer? project-id)))
          {:error {:status 403 :type :project
                   :message "No project selected"}}

          (and (not-empty roles)  ; member role requirements are set
               (not (some (in? mperms) roles)) ; member doesn't have any permitted role
               (not @dev-user?)) ; allow dev users to ignore project role conditions
          {:error {:status 403 :type :project
                   :message "Not authorized (project member)"}}

          (not (or bypass-subscription-lapsed?
                   (not project-id)
                   public-project
                   (and valid-project (pplan/project-unlimited-access? project-id))
                   @dev-user?))
          {:error {:status 402 :type :project
                   :message "This request requires an upgraded plan"}}

          (and ((comp not nil?) authorize-fn)
               (false? (authorize-fn request)))
          {:error {:status 403 :type :project
                   :message "Not authorized (authorize-fn)"}})))

(defmacro with-authorize
  "Wrap request handler body to check if user is authorized to perform the
  request. `request` and `opts` are passed to `authorization-error`."
  [request opts & body]
  (assert ((comp not empty?) body)
          "with-authorize: missing body form")
  `(or (authorization-error ~request ~opts)
       (do ~@body)))

;; Overriding this to allow route handler functions to return result as
;; map value with the value being placed in response :body here.
(extend-protocol Renderable
  clojure.lang.APersistentMap
  (render [resp-map _]
    (merge (with-meta (r/response "") (meta resp-map))
           (if (contains? resp-map :body)
             resp-map
             {:body resp-map}))))
