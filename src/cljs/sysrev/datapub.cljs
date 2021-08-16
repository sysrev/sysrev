(ns sysrev.datapub
  (:require [re-frame.core :refer [reg-sub]]
            [sysrev.data.core :refer [def-data]]))

(def api-endpoint "https://www.datapub.dev/api")
(def websocket-endpoint "wss//www.datapub.dev/ws")

(defn dataset-entity [return]
  (str "query($id: PositiveInt!){datasetEntity(id: $id){" return "}}"))

(defn subscribe-search-dataset [return]
  (str
   "subscription($input: SearchDatasetInput!){searchDataset(input: $input){"
   return
   "}}"))

(def-data :datapub-entity
  :loaded? (fn [db entity-id]
             (boolean (get-in db [:data :datapub :entities entity-id])))
  :method :post
  :uri (constantly api-endpoint)
  :content-type "application/json"
  :content (fn [entity-id]
             {:query (dataset-entity "content externalId mediaType")
              :variables {:id entity-id}})
  :process (fn [{:keys [db]} [entity-id] _ result]
             {:db (assoc-in db [:data :datapub :entities entity-id]
                            (-> result :data :datasetEntity
                                (update :content (comp js->clj js/JSON.parse))))}))

(reg-sub :datapub/entity
         (fn [db [_ entity-id]]
           (get-in db [:data :datapub :entities entity-id])))

(defn subscribe! [& {:keys [on-complete on-data payload]}]
  (if-let [ws (js/WebSocket. websocket-endpoint)]
    (do
      (set!
       (.-onopen ws)
       (fn []
         (.send
          ws
          (js/JSON.stringify
           #js{:type "connection_init"
               :payload #js{}}))))
      (set!
       (.-onmessage ws)
       (fn [message]
         (let [data (js/JSON.parse (.-data message))]
           (case (.-type data)
             "connection_ack"
             #__ (->> {:id "1"
                       :type "start"
                       :payload payload}
                      clj->js
                      js/JSON.stringify
                      (.send ws))
             "complete"
             #__ (do (.close ws 1000 "complete")
                     (when on-complete (on-complete)))
             "data" (-> message .-data js/JSON.parse .-payload on-data)
             "ka" nil))))
      ws)
    (throw (ex-info "Failed to create WebSocket" {:url websocket-endpoint}))))
