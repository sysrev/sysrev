(ns sysrev.notifications.core
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [honeysql.core :as sql]
            [sysrev.db.core :as db :refer [with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :refer [project-user-ids]]))

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

(defn user-id-for-subscriber [subscriber-id]
  (first
   (q/find :notification-subscriber
           {:subscriber-id subscriber-id}
           :user-id)))

(defn messages-for-subscriber [subscriber-id]
  (q/find [:notification_message_subscriber :nms]
          {:nms.subscriber-id subscriber-id}
          :*
          :join [[:notification_message :nm] [:= :nms.message_id :nm.message_id]]
          :order-by [:created :desc]
          :limit 50))

(defn user-ids-for-message [message-id]
  (q/find [:notification-message-subscriber :nms]
          {:nms.message-id message-id}
          :ns.user-id
          :join [[:notification-subscriber :ns]
                 [:= :nms.subscriber-id :ns.subscriber-id]]))

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

(defn unsubscribe-from-topic [subscriber-id topic-id]
  (q/delete :notification_subscriber_topic
            {:subscriber-id subscriber-id :topic-id topic-id}))

(defmulti topic-for-name
  (fn [unique-name & _]
    (-> unique-name (str/split #" " 2) first edn/read-string)))

(defmethod topic-for-name :default [unique-name & opts]
  (apply x-for-y :notification_topic :unique-name unique-name opts))

(defn users-in-group [group-id]
  (q/find [:user-group :ug]
          {:ug.group-id group-id}
          :u.user-id
          :join [[:web-user :u] [:= :ug.user-id :u.user-id]]))

(defmethod topic-for-name :group [unique-name & {:keys [create?] :as opts}]
  (with-transaction
    (or
     (apply x-for-y :notification_topic :unique-name unique-name
            (apply concat (assoc opts :create? false)))
     (when create?
       (let [group-id (-> unique-name (str/split #" " 2) second Long/parseLong)
             topic-id (apply x-for-y :notification_topic :unique-name unique-name
                             (apply concat (assoc opts :returning :topic-id)))]
         (doseq [sid (->> group-id users-in-group
                          (map #(subscriber-for-user
                                 %
                                 :create? true
                                 :returning :subscriber-id)))]
           (subscribe-to-topic sid topic-id))
         (apply x-for-y :notification_topic :unique-name unique-name
                (apply concat (assoc opts :create? false))))))))

(defmethod topic-for-name :project [unique-name
                                    & {:keys [create?] :as opts}]
  (with-transaction
    (or
     (apply x-for-y :notification_topic :unique-name unique-name
            (apply concat (assoc opts :create? false)))
     (when create?
       (let [project-id (-> unique-name (str/split #" " 2) second Long/parseLong)
             topic-id (apply x-for-y :notification_topic :unique-name unique-name
                             (apply concat (assoc opts :returning :topic-id)))]
         (doseq [sid (->> project-id project-user-ids
                          (map #(subscriber-for-user
                                 %
                                 :create? true
                                 :returning :subscriber-id)))]
           (subscribe-to-topic sid topic-id))
         (apply x-for-y :notification_topic :unique-name unique-name
                (apply concat (assoc opts :create? false))))))))

(defmethod topic-for-name :user-notify [unique-name
                                        & {:keys [create?] :as opts}]
  (with-transaction
    (or
     (apply x-for-y :notification_topic :unique-name unique-name
            (apply concat (assoc opts :create? false)))
     (when create?
       (let [user-id (-> unique-name (str/split #" " 2) second Long/parseLong)
             topic-id (apply x-for-y :notification_topic :unique-name unique-name
                             (apply concat (assoc opts :returning :topic-id)))]
         (subscribe-to-topic
          (subscriber-for-user user-id :create? true :returning :subscriber-id)
          topic-id)
         (apply x-for-y :notification_topic :unique-name unique-name
                (apply concat (assoc opts :create? false))))))))

(defmulti message-publisher :type)

(defmethod message-publisher :group-has-new-project [message]
  (publisher-for-project (:project-id message) :create? true))

(defmethod message-publisher :project-has-new-article [message]
  (publisher-for-project (:project-id message) :create? true))

(defmethod message-publisher :project-has-new-user [message]
  (publisher-for-project (:project-id message) :create? true))

(defmethod message-publisher :project-invitation [message]
  (publisher-for-project (:project-id message) :create? true))

(defmulti message-topic-name :type)

(defmethod message-topic-name :group-has-new-project [message]
  (str ":group " (:group-id message)))

(defmethod message-topic-name :project-has-new-article [message]
  (str ":project " (:project-id message)))

(defmethod message-topic-name :project-has-new-user [message]
  (str ":project " (:project-id message)))

(defmethod message-topic-name :project-invitation [message]
  (str ":user-notify " (:user-id message)))

(defn message-topic [message]
  (topic-for-name (message-topic-name message) :create? true))

(defmulti subscriber-ids-to-skip :type)

(defmethod subscriber-ids-to-skip :default [_]
  nil)

(defmethod subscriber-ids-to-skip :group-has-new-project [message]
  (some-> (subscriber-for-user (:adding-user-id message)
                               :returning :subscriber-id)
          vector))

(defmethod subscriber-ids-to-skip :project-has-new-article [message]
  (some-> (subscriber-for-user (:adding-user-id message)
                               :returning :subscriber-id)
          vector))

(defmethod subscriber-ids-to-skip :project-has-new-user [message]
  (some-> (subscriber-for-user (:new-user-id message)
                               :returning :subscriber-id)
          vector))

(defn create-message
  ([message]
   (with-transaction
     (create-message (-> message message-publisher :publisher-id)
                     (message-topic-name message)
                     message)))
  ([publisher-id topic-name message]
   (with-transaction
     (let [topic-id (topic-for-name topic-name
                                    :create? true
                                    :returning :topic-id)
           message-id (q/create :notification_message
                                {:content message
                                 :publisher-id publisher-id
                                 :topic-id topic-id}
                                :returning :message-id)
           nmses (->> (subscribers-for-topic topic-id)
                      (remove (into #{} (subscriber-ids-to-skip message)))
                      (map #(-> {:message-id message-id
                                 :subscriber-id %})))]
       (db/notify! :notification-message
                   (pr-str {:message-id message-id}))
       (q/create :notification-message-subscriber nmses)
       (doseq [n nmses]
         (db/notify! :notification-message-subscriber (pr-str n)))))))

(defn update-message-viewed [message-id subscriber-id]
  (with-transaction
    (let [now (-> "SELECT NOW()" db/raw-query first :now .getTime)]
      (db/notify! :notification-message-subscriber
                  (pr-str {:message-id message-id
                           :subscriber-id subscriber-id
                           :viewed now}))
      (q/modify :notification-message-subscriber
                {:message-id message-id :subscriber-id subscriber-id}
                {:viewed (sql/call :now)}))))
