(ns sysrev.notifications.core
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [honeysql.core :as sql]
            [medley.core :as medley]
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

(defn system-publisher [& {:keys [returning]
                           :or {returning :*}}]
  (x-for-y :notification-publisher
           :unique-publisher-type
           (sql/call :cast "system" :notification-unique-publisher-type)
           :create? true
           :returning returning))

(def subscriber-for-user
  (partial x-for-y :notification_subscriber :user-id))

(defn user-id-for-subscriber [subscriber-id]
  (first
   (q/find :notification-subscriber
           {:subscriber-id subscriber-id}
           :user-id)))

(defn unviewed-system-notifications [subscriber-id & {:as opts}]
  (with-transaction
    (let [user-created (q/find-one
                        :web-user
                        {:user-id (user-id-for-subscriber subscriber-id)}
                        :date-created)]
      (->> (apply q/find
                  [:notification :n]
                  {:publisher-id (system-publisher :returning :publisher-id)
                   :subscriber-id nil}
                  [:consumed :content :created :n.notification-id :publisher-id
                   :subscriber-id :topic-id :viewed]
                  :where (when user-created [:>= :created user-created])
                  :left-join [[:notification-notification-subscriber :nns]
                              [:and [:= :n.notification-id :nns.notification-id]
                               [:= :nns.subscriber-id subscriber-id]]]
                  :order-by [:created :desc]
                  (apply concat opts))
           (map #(-> %
                     (assoc :subscriber-id subscriber-id)
                     (medley/update-existing-in [:content :type] keyword)))))))

(defn notifications-for-subscriber [subscriber-id & {:as opts}]
  (->> (apply q/find
              [:notification_notification_subscriber :nns]
              {:nns.subscriber-id subscriber-id}
              [:consumed :content :created :nns.notification-id :publisher-id
               :subscriber-id :topic-id :viewed]
              :join [[:notification :n] [:= :nns.notification_id :n.notification_id]]
              :order-by [:created :desc]
              (apply concat opts))
       (map #(medley/update-existing-in % [:content :type] keyword))))

(defn user-ids-for-notification [notification-id]
  (q/find [:notification-notification-subscriber :nns]
          {:nns.notification-id notification-id}
          :ns.user-id
          :join [[:notification-subscriber :ns]
                 [:= :nns.subscriber-id :ns.subscriber-id]]))

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

(defmulti notification-publisher :type)

(defmethod notification-publisher :article-reviewed [notification]
  (publisher-for-project (:project-id notification) :create? true))

(defmethod notification-publisher :group-has-new-project [notification]
  (publisher-for-project (:project-id notification) :create? true))

(defmethod notification-publisher :notify-user [notification]
  (publisher-for-user (:user-id notification) :create? true))

(defmethod notification-publisher :project-has-new-article [notification]
  (publisher-for-project (:project-id notification) :create? true))

(defmethod notification-publisher :project-has-new-user [notification]
  (publisher-for-project (:project-id notification) :create? true))

(defmethod notification-publisher :project-invitation [notification]
  (publisher-for-project (:project-id notification) :create? true))

(defmethod notification-publisher :system [_]
  (system-publisher))

(defmulti notification-topic-name :type)

(defmethod notification-topic-name :article-reviewed [notification]
  (str ":project " (:project-id notification)))

(defmethod notification-topic-name :notify-user [notification]
  (str ":user-notify " (:user-id notification)))

(defmethod notification-topic-name :group-has-new-project [notification]
  (str ":group " (:group-id notification)))

(defmethod notification-topic-name :project-has-new-article [notification]
  (str ":project " (:project-id notification)))

(defmethod notification-topic-name :project-has-new-user [notification]
  (str ":project " (:project-id notification)))

(defmethod notification-topic-name :project-invitation [notification]
  (str ":user-notify " (:user-id notification)))

(defmethod notification-topic-name :system [_]
  ":all-users")

(defn notification-topic [notification]
  (topic-for-name (notification-topic-name notification) :create? true))

(defmulti subscriber-ids-to-skip :type)

(defmethod subscriber-ids-to-skip :default [_]
  nil)

(defmethod subscriber-ids-to-skip :article-reviewed [notification]
  (some-> (subscriber-for-user (:user-id notification)
                               :returning :subscriber-id)
          vector))

(defmethod subscriber-ids-to-skip :group-has-new-project [notification]
  (some-> (subscriber-for-user (:adding-user-id notification)
                               :returning :subscriber-id)
          vector))

(defmethod subscriber-ids-to-skip :project-has-new-article [notification]
  (some-> (subscriber-for-user (:adding-user-id notification)
                               :returning :subscriber-id)
          vector))

(defmethod subscriber-ids-to-skip :project-has-new-user [notification]
  (some-> (subscriber-for-user (:new-user-id notification)
                               :returning :subscriber-id)
          vector))

(defn create-notification
  ([notification]
   (with-transaction
     (create-notification (-> notification notification-publisher :publisher-id)
                     (notification-topic-name notification)
                     notification)))
  ([publisher-id topic-name notification]
   (with-transaction
     (let [topic-id (topic-for-name topic-name
                                    :create? true
                                    :returning :topic-id)
           notification-id (q/create :notification
                                {:content notification
                                 :publisher-id publisher-id
                                 :topic-id topic-id}
                                :returning :notification-id)
           nnses (->> (subscribers-for-topic topic-id)
                      (remove (into #{} (subscriber-ids-to-skip notification)))
                      (map #(-> {:notification-id notification-id
                                 :subscriber-id %})))]
       (db/notify! :notification
                   (pr-str {:notification-id notification-id}))
       (q/create :notification-notification-subscriber nnses)
       (doseq [n nnses]
         (db/notify! :notification-notification-subscriber (pr-str n)))))))

(defn update-notifications-consumed [notification-ids subscriber-id]
  (with-transaction
    (let [now (-> "SELECT NOW()" db/raw-query first :now .getTime)]
      (db/notify! :notification-notification-subscriber
                  (pr-str {:consumed now
                           :notification-ids notification-ids
                           :subscriber-id subscriber-id
                           :viewed now}))
      (let [r (q/modify
               :notification-notification-subscriber
               {:notification-id notification-ids
                :subscriber-id subscriber-id}
               {:consumed (sql/call :now)
                :viewed (sql/call :now)})]
        (if-not (zero? r)
          r
          (q/create
           :notification-notification-subscriber
           (map
            #(-> {:notification-id %
                  :subscriber-id subscriber-id
                  :consumed (sql/call :now)
                  :viewed (sql/call :now)})
            notification-ids)))))))

(defn update-notifications-viewed [notification-ids subscriber-id]
  (with-transaction
    (let [now (-> "SELECT NOW()" db/raw-query first :now .getTime)]
      (db/notify! :notification-notification-subscriber
                  (pr-str {:notification-ids notification-ids
                           :subscriber-id subscriber-id
                           :viewed now}))
      (let [r (q/modify
               :notification-notification-subscriber
               {:notification-id notification-ids
                :subscriber-id subscriber-id}
               {:viewed (sql/call :now)})]
        (if-not (zero? r)
          r
          (q/create
           :notification-notification-subscriber
           (map
            #(-> {:notification-id %
                  :subscriber-id subscriber-id
                  :viewed (sql/call :now)})
            notification-ids)))))))
