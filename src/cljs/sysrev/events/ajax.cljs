(ns sysrev.events.ajax
  (:require
   [clojure.spec.alpha :as s]
   [day8.re-frame.http-fx]
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx dispatch trim-v reg-fx]]
   [ajax.core :as ajax]
   [sysrev.shared.util :refer [in? to-uuid]]
   [sysrev.util :refer [integerify-map-keys uuidify-map-keys]]
   [sysrev.subs.core :refer [get-csrf-token]]
   [cognitect.transit :as transit]))

(s/def ::method (and keyword? (in? [:get :post])))
(s/def ::uri string?)
(s/def ::content any?)
(s/def ::on-success vector?)
(s/def ::on-failure vector?)
(s/def ::action-params any?)

;; event interceptor for ajax response handlers
(def handle-ajax
  (re-frame/->interceptor
   :id :handle-ajax
   :before
   (fn [context]
     (let [response (-> context :coeffects :event last)
           success? (and (map? response)
                         (contains? response :result))
           build-id (and (map? response)
                         (:build-id response))
           build-time (and (map? response)
                           (:build-time response))
           result (when-let [result (and (map? response)
                                         (:result response))]
                    result)
           csrf-token (and (map? response)
                           (:csrf-token response))]
       (-> context
           (update-in [:coeffects :event]
                      #(vec (concat (butlast %) [result])))
           (assoc-in [:coeffects :success?] success?)
           (assoc-in [:coeffects :response] response)
           (assoc-in [:coeffects :csrf-token] csrf-token)
           (assoc-in [:coeffects :build-id] build-id)
           (assoc-in [:coeffects :build-time] build-time))))
   :after
   (fn [context]
     (let [{:keys [csrf-token success? response build-id build-time]}
           (:coeffects context)]
       (cond-> context
         csrf-token
         (assoc-in [:effects :set-csrf-token] csrf-token)
         (not success?)
         (assoc-in [:effects :ajax-failure] response)
         (or build-id build-time)
         (assoc-in [:effects :reload-on-new-build]
                   [build-id build-time]))))))

(defn reg-event-ajax
  "Wrapper for standard event-db handler for ajax response"
  ([id db-handler]
   (reg-event-ajax id nil db-handler))
  ([id interceptors db-handler]
   (reg-event-db
    id (concat interceptors [trim-v handle-ajax])
    (fn [db event]
      (db-handler db event)))))

(defn reg-event-ajax-fx
  "Wrapper for standard event-fx handler for ajax response"
  ([id fx-handler]
   (reg-event-ajax-fx id nil fx-handler))
  ([id interceptors fx-handler]
   (reg-event-fx
    id (concat interceptors [trim-v handle-ajax])
    (fn [db event]
      (fx-handler db event)))))

(defn run-ajax [{:keys [db method uri content on-success on-failure action-params content-type]}]
  (let [csrf-token (get-csrf-token db)
        on-failure (or on-failure [:ajax/default-failure])]
    {:http-xhrio
     {:method method
      :uri uri
      :params content
      :timeout (* 2 60 1000)
      :format (condp = content-type
                "application/transit+json" (ajax/transit-request-format)
                "application/json" (ajax/json-request-format))
      :response-format (condp = content-type
                         "application/transit+json" (ajax/transit-response-format)
                         "application/json" (ajax/json-response-format {:keywords? true}))
      :headers (when csrf-token {"x-csrf-token" csrf-token})
      :on-success (cond-> on-success
                    action-params (conj action-params))
      :on-failure (cond-> on-failure
                    action-params (conj action-params))}}))

(s/fdef run-ajax
        :args (s/cat
               :keys (s/keys
                      :req-un [::method ::uri ::on-success]
                      :opt-un [::content ::on-failure ::action-params]))
        :ret map?)

(reg-event-db
 :ajax/default-failure
 [trim-v]
 (fn [db [response]]
   #_ (println (str "request failed: " (pr-str response)))
   db))
