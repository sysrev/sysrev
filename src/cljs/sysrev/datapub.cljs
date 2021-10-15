(ns sysrev.datapub
  (:require [clojure.string :as str]
            [re-frame.core :refer [dispatch reg-event-fx reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.sente :as sente]))

(def api-endpoint
  (delay
    (some-> (.getElementById js/document "datapub-api")
            (.getAttribute "data-datapub-api"))))

(def websocket-endpoint
  (delay
    (some-> (.getElementById js/document "datapub-ws")
            (.getAttribute "data-datapub-ws"))))

(defn dataset-entity [return]
  (str "query($id: PositiveInt!){datasetEntity(id: $id){" return "}}"))

(defn dataset-entities-for-external-id [return]
  (str
   "query($datasetId: PositiveInt!, $externalId: String!){
       dataset(id: $datasetId){
           entities(externalId: $externalId){edges{node{" return "}}}}}"))

(defn subscribe-search-dataset [return]
  (str
   "subscription($input: SearchDatasetInput!){searchDataset(input: $input){"
   return
   "}}"))

(def-data :datapub-entity
  :loaded? (fn [db entity-id]
             (boolean (get-in db [:data :datapub :entities entity-id])))
  :method :post
  :uri #(deref api-endpoint)
  :content-type "application/json"
  :content (fn [entity-id]
             {:query (dataset-entity "content externalId mediaType metadata")
              :variables {:id entity-id}})
  :process (fn [{:keys [db]} [entity-id] _ result]
             (let [{:keys [mediaType metadata] :as entity} (-> result :data :datasetEntity)
                   mediaType (str/lower-case mediaType)
                   entity (if (not= "application/json" mediaType)
                            entity
                            (update entity :content (comp js->clj js/JSON.parse)))
                   entity (if-not metadata
                            entity
                            (update entity :metadata (comp js->clj js/JSON.parse)))]
               {:db (assoc-in db [:data :datapub :entities entity-id] entity)})))

(reg-sub :datapub/entity
         (fn [db [_ entity-id]]
           (get-in db [:data :datapub :entities entity-id])))

(def-data :datapub-entity*
  :loaded? (fn [db entity-id]
             (boolean
              (or (get-in db [:data :datapub :entities entity-id])
                  (get-in db [:data :datapub :entities* entity-id]))))
  :method :post
  :uri #(deref api-endpoint)
  :content-type "application/json"
  :content (fn [entity-id]
             {:query (dataset-entity "externalId mediaType metadata")
              :variables {:id entity-id}})
  :process (fn [{:keys [db]} [entity-id] _ result]
             (let [{:keys [metadata] :as entity} (-> result :data :datasetEntity)
                   entity (if-not metadata
                            entity
                            (update entity :metadata (comp js->clj js/JSON.parse)))]
               {:db (assoc-in db [:data :datapub :entities* entity-id] entity)})))

(reg-sub :datapub/entity*
         (fn [db [_ entity-id]]
           (or (some-> (get-in db [:data :datapub :entities entity-id])
                       (dissoc :content))
               (get-in db [:data :datapub :entities* entity-id]))))

(def-data :datapub-entities-for-external-id
  :loaded? (fn [db dataset-id external-id]
             (boolean (get-in db [:data :datapub :entities-for-external-id dataset-id external-id])))
  :method :post
  :uri #(deref api-endpoint)
  :content-type "application/json"
  :content (fn [dataset-id external-id]
             {:query (dataset-entities-for-external-id "externalCreated id")
              :variables {:datasetId dataset-id :externalId external-id}})
  :process (fn [{:keys [db]} [dataset-id external-id] _ result]
             (let [entity-ids (-> result :data :dataset :entities :edges
                                  (->> (map :node)
                                       (sort-by :externalCreated)
                                       (map :id)))]
               {:db (assoc-in db [:data :datapub :entities-for-external-id dataset-id external-id] entity-ids)})))

(reg-sub :datapub/entities-for-external-id
         (fn [db [_ dataset-id external-id]]
           (get-in db [:data :datapub :entities-for-external-id dataset-id external-id])))

(reg-event-fx :datapub/localhost-subscribe!
        (fn [_ [_ {:keys [on-complete on-data payload]}]]
          {:fx [[::sente/send
                 [[:datapub/subscribe! payload]
                  {:on-success
                   (fn [reply]
                     (doseq [x reply]
                       (on-data (clj->js x)))
                     (when on-complete
                       (on-complete)))}]]]}))

(defn localhost-subscribe!
  "Proxy data through Sente instead of using a WebSocket in order to work
  around Chrome's restrictions on local dev. Necessary for the WebDriver
  tests."
  [& m]
  (dispatch [:datapub/localhost-subscribe! m]))

(defn subscribe! [& {:keys [on-complete on-data payload]}]
  (if (and (exists? js/chrome)
           (or (= "localhost" js/location.host)
               (str/starts-with? js/location.host "localhost:")))
    (localhost-subscribe! :on-complete on-complete :on-data on-data
                          :payload payload)
    (if-let [ws (js/WebSocket. @websocket-endpoint #js["graphql-ws"])]
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
               "data" (-> data .-payload on-data)
               "error" (js/console.error "Error in datapub subscription:" data)
               "ka" nil))))
        ws)
      (throw (ex-info "Failed to create WebSocket" {:url @websocket-endpoint})))))
