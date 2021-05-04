(ns sysrev.shared.notifications)

(def single->combined
  {:article-reviewed :article-reviewed-combined
   :project-has-new-article :project-has-new-article-combined
   :project-has-new-user :project-has-new-user-combined})

(def combined->single
  (->> single->combined (map (comp vec reverse)) (into {})))

(defmulti uncombine-notification (comp :type :content))

(defmethod uncombine-notification :default [{:keys [content notification-ids]
                                             :as notification}]
  (if-let [type (combined->single (:type content))]
    (let [base (-> (dissoc notification :notification-ids)
                   (assoc :partial? true)
                   (assoc-in [:content :type] type))]
      (map #(assoc base :notification-id %) notification-ids))
    [notification]))

(defmethod uncombine-notification :project-has-new-user-combined
  [{:keys [content notification-ids] :as notification}]
  (let [base (-> (dissoc notification :notification-ids)
                 (update :content dissoc :new-user-names)
                 (assoc :partial? true)
                 (assoc-in [:content :type] :project-has-new-user))]
    (map #(-> (assoc base :notification-id %)
              (update :content assoc :new-user-name %2))
         notification-ids (:new-user-names content))))

(defn combine-notifications-of-type* [group-by-fn f notifications]
  (->> (group-by group-by-fn notifications)
       (mapcat
        (fn [[k ns]]
          (if (>= 1 (count ns))
            ns
            (let [viewed (map :viewed ns)]
              (-> ns first
                  (dissoc :notification-id)
                  (assoc
                   :created (->> ns (map :created) sort last)
                   :notification-ids (map :notification-id ns)
                   :viewed (when-not (some nil? viewed)
                             (last (sort viewed))))
                  (f k ns)
                  vector)))))))

(defmulti combine-notifications-of-type (fn [type _notifications] type))

(defmethod combine-notifications-of-type :default [_ notifications]
  notifications)

(defmethod combine-notifications-of-type :article-reviewed [_ notifications]
  (combine-notifications-of-type*
   (comp :project-id :content)
   (fn [n project-id ns]
     (assoc n :content {:article-count (count ns)
                        :project-id project-id
                        :project-name (-> ns first :content :project-name)
                        :type :article-reviewed-combined}))
   notifications))

(defmethod combine-notifications-of-type :project-has-new-article [_ notifications]
  (combine-notifications-of-type*
   (comp :project-id :content)
   (fn [n project-id ns]
     (assoc n :content {:article-count (count ns)
                        :project-id project-id
                        :project-name (-> ns first :content :project-name)
                        :type :project-has-new-article-combined}))
   notifications))

(defmethod combine-notifications-of-type :project-has-new-user [_ notifications]
  (combine-notifications-of-type*
   (comp :project-id :content)
   (fn [n project-id ns]
     (assoc n :content {:image-uri (some (comp :image-uri :content) ns)
                        :new-user-names (map (comp :new-user-name :content) ns)
                        :project-id project-id
                        :project-name (-> ns first :content :project-name)
                        :type :project-has-new-user-combined}))
   notifications))

(defn combine-notifications
  ([notifications]
   (->> (group-by (comp keyword :type :content) notifications)
        (mapcat (fn [[type ns]]
                  (->> ns (sort-by :created) reverse
                       (combine-notifications-of-type type))))))
  ([group-by-fn notifications]
   (->> notifications
        (group-by group-by-fn)
        (mapcat (comp combine-notifications second)))))
