(ns sysrev.events.ajax
  (:require
   [clojure.spec.alpha :as s]
   [day8.re-frame.http-fx]
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe dispatch trim-v reg-fx]]
   [sysrev.shared.util :refer [in? to-uuid]]
   [sysrev.util :refer [integerify-map-keys uuidify-map-keys]]
   [ajax.core :as ajax]))

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
           result (when-let [result (and (map? response)
                                         (:result response))]
                    (-> result integerify-map-keys uuidify-map-keys))
           csrf-token (and (map? response)
                           (:csrf-token response))]
       (-> context
           (update-in [:coeffects :event]
                      #(vec (concat (butlast %) [result])))
           (assoc-in [:coeffects :success?] success?)
           (assoc-in [:coeffects :response] response)
           (assoc-in [:coeffects :csrf-token] csrf-token))))
   :after
   (fn [context]
     (let [{:keys [csrf-token success? response]}
           (:coeffects context)]
       (cond-> context
         csrf-token
         (assoc-in [:effects :set-csrf-token] csrf-token)
         (not success?)
         (assoc-in [:effects :ajax-failure] response))))))

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

(defn run-ajax [{:keys [method uri content on-success on-failure action-params]}]
  (let [csrf-token @(subscribe [:csrf-token])
        on-failure (or on-failure [:ajax/default-failure])]
    {:http-xhrio
     {:method method
      :uri uri
      :params content
      :timeout 5000
      :format (ajax/json-request-format)
      :response-format (ajax/json-response-format {:keywords? true})
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
   (println (str "request failed: " (pr-str response)))
   db))
