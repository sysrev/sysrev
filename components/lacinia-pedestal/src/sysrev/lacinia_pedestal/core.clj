(ns sysrev.lacinia-pedestal.core
  (:require
   [cheshire.core :as json]
   [clojure.core.async :refer [>!! alt!! chan close! put! thread]]
   [clojure.stacktrace]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [com.walmartlabs.lacinia :as lacinia]
   [com.walmartlabs.lacinia.constants :as constants]
   [com.walmartlabs.lacinia.executor :as executor]
   [com.walmartlabs.lacinia.parser :as parser]
   [com.walmartlabs.lacinia.pedestal.internal :as pedestal-internal]
   [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]
   [com.walmartlabs.lacinia.pedestal2 :as pedestal2]
   [com.walmartlabs.lacinia.resolve :as resolve]
   [io.pedestal.http :as http]
   [io.pedestal.http.impl.servlet-interceptor]
   [io.pedestal.http.ring-middlewares :as ring-middlewares]
   [io.pedestal.interceptor :as interceptor]
   [io.pedestal.log]
   [medley.core :as medley]
   [ring.util.request :as rur]))

(defn graphiql-ide-handler [opts]
  (fn [request]
    ((pedestal2/graphiql-ide-handler
      (medley/deep-merge
       {:ide-connection-params
        {:authorization (get-in request [:headers "authorization"])}}
       opts))
     request)))

(def body-data-interceptor
  (interceptor/interceptor
   {:name ::body-data
    :enter
    (fn [context]
      (let [request (:request context)
            content-type (str/lower-case (rur/content-type request))]
        (case content-type
          "application/json"
          (update-in context [:request :body] slurp)

          "multipart/form-data"
          (->> (get-in request [:multipart-params "operations"])
               (assoc request :body)
               (assoc context :request))

          (assoc context :response
                 {:status 400
                  :headers {}
                  :body "Bad Request: Must be application/json or multipart/form-data"}))))}))

(defn object-path
  "Converts an object-path (https://github.com/mariocasciaro/object-path)
  to a form usable with get-in. Path segments that look like natural integers
  are converted to Longs. str-key-fn will be called on string path segments.

  Examples:
  (object-path \"a.0\")
  -> [\"a\" 0]

  (object-path [\"a\" \"b\" 1] keyword)
  -> [:a :b 1]"
  [str-or-seq & [str-key-fn]]
  (let [str-key-fn (or str-key-fn identity)]
    (if (string? str-or-seq)
      (object-path (str/split str-or-seq #"\.") str-key-fn)
      (mapv (fn [s]
              (if-not (string? s)
                s
                (if (re-matches #"\d+" s)
                  (Long/parseLong s)
                  (str-key-fn s))))
            str-or-seq))))

(defn clear-graphql-data [context]
  (update context :request dissoc :graphql-query :graphql-vars :graphql-operation-name))

(def ^{:doc "Implements the file upload part of the multipart request spec:
https://github.com/jaydenseric/graphql-multipart-request-spec"}
  graphql-data-interceptor
  (interceptor/interceptor
   {:name ::graphql-data
    :enter
    (fn [context]
      (try
        (let [payload (-> context :request :body (json/parse-string keyword))
              {:keys [query variables]
               operation-name :operationName} payload
              multipart (-> context :request :multipart-params)
              mappings (some-> multipart (get "map") json/parse-string)
              multipart-map
              #__ (reduce
                   (fn [m [k v]]
                     (let [part (get multipart k)
                           part-data (if (string? part)
                                       part
                                       (some-> part
                                               (assoc :path (.toPath (:tempfile part)))
                                               (dissoc :tempfile)))]
                       (reduce #(assoc-in % (object-path %2 keyword) part-data)
                               m v)))
                   nil
                   mappings)]
          (update context :request
                  assoc
                  :graphql-query query
                  :graphql-vars (medley/deep-merge variables (:variables multipart-map))
                  :graphql-operation-name operation-name))
        (catch Exception e
          (assoc context :response
                 {:status 400
                  :headers {}
                  :body
                  {:message (str "Invalid request: " (.getMessage e))}}))))
    :leave clear-graphql-data
    :error (fn [context exception]
             (-> (clear-graphql-data context)
                 (pedestal-internal/add-error exception)))}))

;; https://github.com/walmartlabs/lacinia-pedestal/blob/b1ff019e88d3ad066f327bca49bac7a1c4b48e53/src/com/walmartlabs/lacinia/pedestal/subscriptions.clj#L315-L328
(defn execute-operation [context parsed-query]
  (let [ch (chan 1)]
    (-> context
        (get-in [:request :lacinia-app-context])
        (assoc
         ::lacinia/connection-params (:connection-params context)
         constants/parsed-query-key parsed-query)
        executor/execute-query
        (resolve/on-deliver! (fn [response]
                               (put! ch (assoc context :response response))))
        ;; Don't execute the query in a limited go block thread
        thread)
    ch))

(defn- remove-realized [promises]
  (if (< 1 (count promises))
    (filterv (comp not realized?) promises)
    promises))

;; https://github.com/walmartlabs/lacinia-pedestal/blob/b1ff019e88d3ad066f327bca49bac7a1c4b48e53/src/com/walmartlabs/lacinia/pedestal/subscriptions.clj#L330-L380
;; Modified to make source-stream block when response-data-ch is full and to remove a race
;; condition with close!.
(defn execute-subscription [context parsed-query]
  (let [{:keys [::subscriptions/values-chan-fn request]} context
        source-stream-ch (values-chan-fn)
        {:keys [id shutdown-ch response-data-ch]} request
        source-stream (fn [value]
                        (if (some? value)
                          (>!! source-stream-ch value)
                          (close! source-stream-ch)))
        app-context (-> context
                        (get-in [:request :lacinia-app-context])
                        (assoc
                         ::lacinia/connection-params (:connection-params context)
                         constants/parsed-query-key parsed-query))
        cleanup-fn (executor/invoke-streamer app-context source-stream)]
    (future
      (loop [promises []]
        (alt!!

          ;; TODO: A timeout?

          ;; This channel is closed when the client sends a "stop" message
          shutdown-ch
          (do
            (close! response-data-ch)
            (cleanup-fn))

          source-stream-ch
          ([value]
           (if (some? value)
             (let [p (promise)]
               (-> app-context
                   (assoc ::executor/resolved-value value)
                   executor/execute-query
                   (resolve/on-deliver! (fn [response]
                                          (>!! response-data-ch
                                               {:type :data
                                                :id id
                                                :payload response})
                                          (deliver p true))))
               (recur (conj (remove-realized promises) p)))
             (future
               (doseq [p promises] @p)
               ;; The streamer has signaled that it has exhausted the subscription.
               (>!! response-data-ch {:type :complete
                                      :id id})
               (close! response-data-ch)
               (cleanup-fn)))))))

    ;; Return the context unchanged, it will unwind while the above process
    ;; does the real work.
    context))

;; https://github.com/walmartlabs/lacinia-pedestal/blob/b1ff019e88d3ad066f327bca49bac7a1c4b48e53/src/com/walmartlabs/lacinia/pedestal/subscriptions.clj#L382-L393
(def execute-operation-interceptor
  "Executes a mutation or query operation and sets the :response key of the context,
  or executes a long-lived subscription operation."
  (interceptor/interceptor
   {:name ::execute-operation
    :enter (fn [context]
             (let [request (:request context)
                   parsed-query (:parsed-lacinia-query request)
                   operation-type (-> parsed-query parser/operations :type)]
               (if (= operation-type :subscription)
                 (execute-subscription context parsed-query)
                 (execute-operation context parsed-query))))}))

(def error-logging-interceptor
  (interceptor/interceptor
   {:name ::error-logging
    :error (fn [_context ^Throwable t]
             (log/error t)
             (throw t))}))

(defn api-interceptors [compiled-schema app-context]
  [pedestal2/initialize-tracing-interceptor
   pedestal2/json-response-interceptor
   pedestal2/error-response-interceptor
   (ring-middlewares/multipart-params)
   body-data-interceptor
   graphql-data-interceptor
   pedestal2/status-conversion-interceptor
   pedestal2/missing-query-interceptor
   (pedestal2/query-parser-interceptor compiled-schema)
   pedestal2/disallow-subscriptions-interceptor
   pedestal2/prepare-query-interceptor
   (pedestal2/inject-app-context-interceptor app-context)
   pedestal2/enable-tracing-interceptor
   pedestal2/query-executor-handler])

(defn subscription-interceptors [compiled-schema app-context]
  [subscriptions/exception-handler-interceptor
   error-logging-interceptor
   subscriptions/send-operation-response-interceptor
   (subscriptions/query-parser-interceptor compiled-schema)
   (subscriptions/inject-app-context-interceptor app-context)
   execute-operation-interceptor])

;; Adapted from https://tonsky.me/blog/pedestal/
;; I attempted an implementation as described in
;; https://github.com/pedestal/pedestal/issues/623#issuecomment-508853264
;; but couldn't get anywhere.
;; Filtering out Broken pipe reporting
;; io.pedestal.http.impl.servlet-interceptor/error-stylobate
(defn error-stylobate [{:keys [request servlet-response] :as context} exception]
  (let [cause (clojure.stacktrace/root-cause exception)]
    (if (and (instance? java.io.IOException cause)
             (= "Broken pipe" (.getMessage cause)))
      (io.pedestal.log/info :msg (str "Broken pipe for " (-> request :request-method name str/upper-case) " " (:uri request)))
      (do
        (io.pedestal.log/error
         :msg "error-stylobate triggered"
         :context context)
        ;; Put exception on its own line so stack trace doesn't get truncated due to size
        (io.pedestal.log/error :exception exception)))
    (@#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate context)))

;; io.pedestal.http.impl.servlet-interceptor/stylobate
(def stylobate
  (io.pedestal.interceptor/interceptor
   {:name ::stylobate
    :enter @#'io.pedestal.http.impl.servlet-interceptor/enter-stylobate
    :leave @#'io.pedestal.http.impl.servlet-interceptor/leave-stylobate
    :error error-stylobate}))

(defrecord Pedestal [bound-port config opts secrets-manager service service-map service-map-fn]
  component/Lifecycle
  (start [this]
    (if service
      this
      (let [opts (assoc (:pedestal config) :env (:env config))]
        (if (and (nil? (:port opts)) (not= :test (:env opts)))
          (throw (RuntimeException. "Port cannot be nil outside of :test env."))
          (let [service-map (service-map-fn
                             (update opts :port #(or % 0)) ; Prevent pedestal exception
                             this)
                service (with-redefs [io.pedestal.http.impl.servlet-interceptor/stylobate stylobate]
                          (if (:port opts)
                            (http/start (http/create-server service-map))
                            (http/create-server (assoc service-map :port 0))))
                bound-port (some-> service :io.pedestal.http/server
                                   .getURI .getPort)]
            (if (and bound-port (pos-int? bound-port))
              (log/info "Started Pedestal on port" bound-port)
              (log/info "Started Pedestal with no ports"))
            (assoc this
                   :bound-port bound-port
                   :opts opts
                   :service-map service-map
                   :service service))))))
  (stop [this]
    (if-not service-map
      this
      (do
        (when (:port opts)
          (http/stop service)
          (log/info "Stopped Pedestal"))
        (assoc this :bound-port nil :service nil :service-map nil)))))

(defn pedestal [service-map-fn]
  (map->Pedestal {:service-map-fn service-map-fn}))
