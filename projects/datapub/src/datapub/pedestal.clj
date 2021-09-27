(ns datapub.pedestal
  (:require [clojure.core.async :refer [>!! alt!! chan close! put! thread]]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.constants :as constants]
            [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.parser :as parser]
            [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]
            [com.walmartlabs.lacinia.pedestal2 :as pedestal2]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datapub.graphql :as graphql]
            [io.pedestal.http :as http]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]
            [taoensso.timbre :as t]))

(defn graphiql-ide-handler [request]
  ((pedestal2/graphiql-ide-handler
    {:ide-connection-params
     {:authorization (get-in request [:headers "authorization"])}})
   request))

; https://github.com/walmartlabs/lacinia-pedestal/blob/b1ff019e88d3ad066f327bca49bac7a1c4b48e53/src/com/walmartlabs/lacinia/pedestal/subscriptions.clj#L315-L328
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

; https://github.com/walmartlabs/lacinia-pedestal/blob/b1ff019e88d3ad066f327bca49bac7a1c4b48e53/src/com/walmartlabs/lacinia/pedestal/subscriptions.clj#L330-L380
; Modified to make source-stream block when response-data-ch is full and to remove a race
; condition with close!.
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

; https://github.com/walmartlabs/lacinia-pedestal/blob/b1ff019e88d3ad066f327bca49bac7a1c4b48e53/src/com/walmartlabs/lacinia/pedestal/subscriptions.clj#L382-L393
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

(defn subscription-interceptors [compiled-schema app-context]
  [subscriptions/exception-handler-interceptor
   subscriptions/send-operation-response-interceptor
   (subscriptions/query-parser-interceptor compiled-schema)
   (subscriptions/inject-app-context-interceptor app-context)
   execute-operation-interceptor])

(def allowed-origins
  {:dev (constantly true)
   :prod #{"https://www.datapub.dev" "https://sysrev.com" "https://staging.sysrev.com"}
   :staging #{"https://www.datapub.dev" "https://sysrev.com" "https://staging.sysrev.com"}
   :test (constantly true)})

(defn cors-preflight [request allowed-origins]
  (let [origin (some-> (get-in request [:headers "origin"]) str/lower-case)]
    {:status 204
     :headers
     {"Access-Control-Allow-Headers" "Authorization, Content-Type, x-csrf-token"
      "Access-Control-Max-Age" "86400"
      "Access-Control-Allow-Methods" "OPTIONS, POST"
      "Access-Control-Allow-Origin" (when (allowed-origins origin) origin)}}))

(defn service-map [{:keys [env port] :as opts} pedestal]
  (let [compiled-schema (graphql/load-schema)
        app-context {:opts opts :pedestal pedestal}
        interceptors (into
                      [(cors/allow-origin (allowed-origins env))]
                      (pedestal2/default-interceptors compiled-schema app-context))
        routes (into #{["/api" :options
                        #(cors-preflight % (allowed-origins env))
                        :route-name ::graphql-api-cors-preflight]
                       ["/api" :post interceptors :route-name ::graphql-api]
                       ["/ide" :get graphiql-ide-handler :route-name ::graphiql-ide]}
                     (pedestal2/graphiql-asset-routes "/assets/graphiql"))]
    (-> {:env env
         :graphql-schema compiled-schema
         ::http/routes routes
         ::http/port port
         ::http/host "localhost"
         ::http/type :jetty
         ::http/join? false}
        pedestal2/enable-graphiql
        (pedestal2/enable-subscriptions
         graphql/load-schema
         {:app-context app-context
          :subscription-interceptors
          #__ (subscription-interceptors compiled-schema app-context)
          :values-chan-fn #(chan 10)}))))

(defrecord Pedestal [bound-port opts service service-map service-map-fn]
  component/Lifecycle
  (start [this]
    (if service
      this
      (if (and (nil? (:port opts)) (not= :test (:env opts)))
        (throw (RuntimeException. "Port cannot be nil outside of :test env."))
        (let [service-map (service-map-fn
                           (update opts :port #(or % 0)) ; Prevent pedestal exception
                           this)
              service (if (:port opts)
                        (http/start (http/create-server service-map))
                        (http/create-server (assoc service-map :port 0)))
              bound-port (some-> service :io.pedestal.http/server
                                 .getURI .getPort)]
          (if (and bound-port (pos-int? bound-port))
            (t/info "Started Pedestal on port" bound-port)
            (t/info "Started Pedestal with no ports"))
          (assoc this
                 :bound-port bound-port
                 :service-map service-map
                 :service service)))))
  (stop [this]
    (if-not service-map
      this
      (do
        (when (:port opts)
          (http/stop service)
          (t/info "Stopped Pedestal"))
        (assoc this :bound-port nil :service nil :service-map nil)))))

(defn pedestal [{:keys [opts]}]
  (map->Pedestal {:opts opts :service-map-fn service-map}))
