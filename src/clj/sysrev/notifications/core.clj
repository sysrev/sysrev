(ns sysrev.notifications.core
  (:require [clojure.string :as str]
            [honeysql.core :as hsql]
            [sysrev.db.core :refer [with-transaction]]
            [sysrev.db.queries :as q]))

(defn x-for-y [table-name col-name col-value
               & {:keys [create? returning]
                  :or {returning :*}}]
  (or
   (first
    (q/find table-name
            {col-name col-value}
            returning))
   (when create?
     (with-transaction
       (or
        (q/create table-name
                  {col-name col-value}
                  :prepare #(assoc % :on-conflict [col-name] :do-nothing [])
                  :returning returning)
        (x-for-y table-name col-name col-value))))))

(def publisher-for-project
  (partial x-for-y :notification_publisher :project-id))

(def publisher-for-user
  (partial x-for-y :notification_publisher :user-id))

(def subscriber-for-user
  (partial x-for-y :notification_subscriber :user-id))

(defn messages-for-subscriber [subscriber-id & [new?]]
  (q/find [:notification_message_subscriber :nms]
          {:nms.subscriber-id subscriber-id}
          :*
          :join [[:notification_message :nm] [:= :nms.message_id :nm.message_id]]
          :order-by [:created :desc]
          :limit 50))

(defn subscribe-to-topic [subscriber-id topic-id]
  (q/create :notification_subscriber_topic
            {:subscriber-id subscriber-id :topic-id topic-id}
            :prepare #(assoc %
                             :on-conflict [:subscriber-id :topic-id]
                             :do-nothing [])))

(defn subscribers-for-topic [topic-id]
  (q/find :notification_subscriber_topic
          {:topic-id topic-id}
          :subscriber-id))

(defn topic-for-name [unique-name & {:keys [create?] :as opts}]
  (if (and create? (str/starts-with? unique-name ":user-notify "))
    (with-transaction
      (or
       (apply x-for-y :notification_topic :unique-name unique-name
              (apply concat (assoc opts :create? false)))
       (let [user-id (-> unique-name (str/split #" " 2) second Long/parseLong)
             topic-id (apply x-for-y :notification_topic :unique-name unique-name
                             (apply concat (assoc opts :returning :topic-id)))]
         (subscribe-to-topic
          (subscriber-for-user user-id :create? true :returning :subscriber-id)
          topic-id)
         (apply x-for-y :notification_topic :unique-name unique-name
                (apply concat (assoc opts :create? false))))))
    (apply x-for-y :notification_topic :unique-name unique-name
           (apply concat opts))))

(defmulti message-publisher :type)

(defmethod message-publisher :project-invitation [message]
  (publisher-for-project (:project-id message) :create? true))

(defmethod message-publisher :project-has-new-user [message]
  (publisher-for-project (:project-id message) :create? true))

(defmulti message-topic-name :type)

(defmethod message-topic-name :project-invitation [message]
  (str ":user-notify " (:user-id message)))

(defmethod message-topic-name :project-has-new-user [message]
  (str ":project " (:project-id message)))

(defn message-topic [message]
  (topic-for-name (message-topic-name message) :create? true))

(defn create-message
  ([message]
   (with-transaction
     (create-message (-> message message-publisher :publisher-id)
                     (message-topic-name message)
                     message)))
  ([publisher-id topic-name content]
   (with-transaction
     (let [topic-id (topic-for-name topic-name
                                    :create? true
                                    :returning :topic-id)
           message-id (q/create :notification_message
                                {:content content
                                 :publisher-id publisher-id
                                 :topic-id topic-id}
                                :returning :message-id)]
       (q/create :notification_message_subscriber
                 (map #(-> {:message-id message-id
                            :subscriber-id %})
                      (subscribers-for-topic topic-id)))))))

(defn update-message-viewed [message-id subscriber-id]
  (prn message-id subscriber-id
   (q/modify :notification_message_subscriber
             {:message-id message-id :subscriber-id subscriber-id}
             {:viewed (hsql/call :now)})))
