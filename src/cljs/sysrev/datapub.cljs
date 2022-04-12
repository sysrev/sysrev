(ns sysrev.datapub
  (:require [clojure.string :as str]
            [re-frame.core :refer [dispatch reg-sub]]
            [sysrev.ajax :as ajax]
            [sysrev.data.core :refer [def-data]]))

(def websocket-endpoint
  (delay
    (some-> (.getElementById js/document "datapub-ws")
            (.getAttribute "data-datapub-ws"))))

(defn dataset-entity [return]
  (str "query($id: ID!){datasetEntity(id: $id){" return "}}"))

(defn dataset-entities-for-external-id [return]
  (str
   "query($datasetId: ID!, $externalId: String!){
       dataset(id: $datasetId){
           entities(externalId: $externalId){edges{node{" return "}}}}}"))

(defn dataset-entities-for-grouping-id [return]
  (str
   "query($datasetId: ID!, $groupingId: String!){
       dataset(id: $datasetId){
           entities(groupingId: $groupingId){edges{node{" return "}}}}}"))

(defn subscribe-search-dataset [return]
  (str
   "subscription($input: SearchDatasetInput!){searchDataset(input: $input){"
   return
   "}}"))

(def-data :datapub-entity
  :loaded? (fn [db entity-id]
             (boolean (get-in db [:data :datapub :entities entity-id])))
  :method :post
  :uri #(deref ajax/graphql-endpoint)
  :content-type "application/json"
  :content (fn [entity-id]
             {:query (dataset-entity "contentUrl externalCreated externalId groupingId mediaType metadata")
              :variables {:id entity-id}})
  :process (fn [{:keys [db]} [entity-id] _ result]
             (let [{:keys [metadata] :as entity} (-> result :data :datasetEntity)
                   entity (if-not metadata
                            entity
                            (update entity :metadata (comp js->clj js/JSON.parse)))]
               {:db (assoc-in db [:data :datapub :entities entity-id] entity)})))

(reg-sub :datapub/entity
         (fn [db [_ entity-id]]
           (get-in db [:data :datapub :entities entity-id])))

(def-data :datapub-entity-content
  :loaded? (fn [db content-url]
             (boolean (get-in db [:data :datapub :entity-content content-url])))
  :method :get
  :uri (fn [content-url] content-url)
  :process (fn [{:keys [db]} [content-url] _ result]
             {:db (assoc-in db [:data :datapub :entity-content content-url] result)}))

(reg-sub :datapub/entity-content
         (fn [db [_ content-url]]
           (get-in db [:data :datapub :entity-content content-url])))

(def-data :datapub-entities-for-external-id
  :loaded? (fn [db dataset-id external-id]
             (boolean (get-in db [:data :datapub :entities-for-external-id dataset-id external-id])))
  :method :post
  :uri #(deref ajax/graphql-endpoint)
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

(def-data :datapub-entities-for-grouping-id
  :loaded? (fn [db dataset-id grouping-id]
             (boolean (get-in db [:data :datapub :entities-for-grouping-id dataset-id grouping-id])))
  :method :post
  :uri #(deref ajax/graphql-endpoint)
  :content-type "application/json"
  :content (fn [dataset-id grouping-id]
             {:query (dataset-entities-for-grouping-id "externalCreated id")
              :variables {:datasetId dataset-id :groupingId grouping-id}})
  :process (fn [{:keys [db]} [dataset-id grouping-id] _ result]
             (let [entity-ids (-> result :data :dataset :entities :edges
                                  (->> (map :node)
                                       (sort-by :externalCreated)
                                       (map :id)))]
               {:db (assoc-in db [:data :datapub :entities-for-grouping-id dataset-id grouping-id] entity-ids)})))

(reg-sub :datapub/entities-for-grouping-id
         (fn [db [_ dataset-id grouping-id]]
           (get-in db [:data :datapub :entities-for-grouping-id dataset-id grouping-id])))

(defn subscribe! [& {:keys [on-complete on-data payload]}]
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
    (throw (ex-info "Failed to create WebSocket" {:url @websocket-endpoint}))))
