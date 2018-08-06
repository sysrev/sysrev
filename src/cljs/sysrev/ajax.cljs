(ns sysrev.ajax
  (:require [clojure.spec.alpha :as s]
            [cognitect.transit :as transit]
            [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [re-frame.core :as re-frame :refer
             [dispatch reg-sub reg-event-db reg-event-fx trim-v reg-fx]]
            [sysrev.util :refer [integerify-map-keys uuidify-map-keys]]
            [sysrev.shared.util :refer [in? to-uuid]]))

(s/def ::method (and keyword? (in? [:get :post])))
(s/def ::uri string?)
(s/def ::content any?)
(s/def ::on-success vector?)
(s/def ::on-failure vector?)
(s/def ::action-params any?)

(defn get-csrf-token [db] (:csrf-token db))
(reg-sub :csrf-token get-csrf-token)
(defn get-build-id [db] (:build-id db))
(reg-sub :build-id get-build-id)
(defn get-build-time [db] (:build-time db))
(reg-sub :build-time get-build-time)

(reg-event-db
 :set-csrf-token
 [trim-v]
 (fn [db [csrf-token]]
   (assoc db :csrf-token csrf-token)))

(reg-fx :set-csrf-token #(dispatch [:set-csrf-token %]))

(reg-event-db
 :set-build-id
 [trim-v]
 (fn [db [build-id]]
   (assoc db :build-id build-id)))

(reg-event-db
 :set-build-time
 [trim-v]
 (fn [db [build-time]]
   (assoc db :build-time build-time)))

(reg-fx
 :ajax-failure
 (fn [response]
   nil))

(reg-event-fx
 ::reload-on-new-build
 [trim-v]
 (fn [{:keys [db]} [build-id build-time]]
   (let [cur-build-id (get-build-id db)
         cur-build-time (get-build-time db)]
     (merge
      {:dispatch-n
       (->> (list (when (nil? cur-build-id)
                    [:set-build-id build-id])
                  (when (nil? cur-build-time)
                    [:set-build-time build-time]))
            (remove nil?))}
      (when (or (and build-id cur-build-id
                     (not= build-id cur-build-id))
                (and build-time cur-build-time
                     (not= build-time cur-build-time)))
        {:reload-page [true 50]})))))

(reg-fx
 :reload-on-new-build
 (fn [[build-id build-time]]
   (when (or build-id build-time)
     (dispatch [::reload-on-new-build [build-id build-time]]))))

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
           error (when-let [error (and (map? response)
                                       (map? (-> response :response))
                                       (-> response :response :error))]
                   error)
           csrf-token (and (map? response)
                           (:csrf-token response))]
       (-> context
           (update-in [:coeffects :event]
                      #(vec (concat (butlast %) [result])))
           (#(if (nil? error)
               %
               (assoc-in % [:coeffects :error] error)))
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
                         "application/transit+json"
                         (ajax/transit-response-format
                          :json {:handlers {"u" cljs.core/uuid}})
                         "application/json"
                         (ajax/json-response-format {:keywords? true}))
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
