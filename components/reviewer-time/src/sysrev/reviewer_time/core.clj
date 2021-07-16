(ns sysrev.reviewer-time.core
  (:require [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.selection :as selection]
            [medley.core :as medley]
            [sysrev.db.core :as db])
  (:import (java.sql Timestamp)
           (java.time Duration Instant LocalDateTime ZoneId ZoneOffset)))

(set! *warn-on-reflection* true)

(def ^{:doc "The time credited after the last recorded event."}
  ^Duration
  after-duration
  (Duration/ofMinutes 2))

(def ^{:doc "The duration that must elapse after an event before a reviewer is considered to be idle."}
  ^Duration
  idle-duration
  (Duration/ofHours 1))

(defn ^LocalDateTime Date->LocalDateTime [^java.util.Date date]
  (-> date
      .toInstant
      (LocalDateTime/ofInstant (ZoneId/systemDefault))))

(defn ^LocalDateTime Long->LocalDateTime [^Long long]
  (-> long
      Instant/ofEpochMilli
      (LocalDateTime/ofInstant (ZoneId/of "UTC"))))

(defn ^LocalDateTime Timestamp->LocalDateTime [^Timestamp ts]
  (-> ts
      .toInstant
      (LocalDateTime/ofInstant (ZoneId/of "UTC"))))

(defn ^LocalDateTime ->LocalDateTime [x]
  (cond
    (instance? LocalDateTime x) x
    (instance? java.util.Date x)
    #__ (Date->LocalDateTime x)
    (instance? java.sql.Timestamp x)
    #__ (Timestamp->LocalDateTime x)
    (integer? x) (Long->LocalDateTime x)))

(defn create-events! [connectable events]
  (->> events
       (map #(select-keys % #{:article-id :event-type :project-id :user-id}))
       (hash-map :insert-into :reviewer-event :values)
       (db/execute! connectable)))

(defn to-intervals [events]
  (when (seq events)
    (let [{:reviewer-event/keys [article-id created project-id]} (first events)
          created (->LocalDateTime created)]
      (loop [{:keys [article-id project-id ^LocalDateTime last-event-time]
              :as current}
             #__ {:article-id article-id
                  :project-id project-id
                  :last-event-time created
                  :start created}
             [event & more] (sort-by :created events)
             intervals (transient [])]
        (if-not event
          (if more
            (recur current more intervals)
            (-> intervals
                (conj! (assoc current :end (.plus last-event-time after-duration)))
                persistent!))
          (let [created (->LocalDateTime (:reviewer-event/created event))
                d (Duration/between last-event-time created)
                d* (if (neg? (compare d idle-duration)) d after-duration)]
            (if (or (not= article-id (:reviewer-event/article-id event))
                    (not= project-id (:reviewer-event/project-id event))
                    (pos? (compare d idle-duration)))
              (recur {:article-id (:reviewer-event/article-id event)
                      :project-id (:reviewer-event/project-id event)
                      :last-event-time created
                      :start created}
                     more
                     (conj! intervals (assoc current :end (.plus last-event-time d*))))
              (recur (assoc current :last-event-time created)
                     more intervals))))))))

(defn ^Duration intervals->total-duration [intervals]
  (->> intervals
       (map #(Duration/between (:start %) (:end %)))
       (reduce #(.plus ^Duration % %2) Duration/ZERO)))

(defn get-project-events
  [connectable project-id
   & {:keys [^LocalDateTime start ^LocalDateTime end user-ids]}]
  (db/execute!
   connectable
   {:select :*
    :from :reviewer-event
    :where [:and
            [:= :project-id project-id]
            (when start
              [:>= :created (.minus start idle-duration)])
            (when end
              [:<= :created (.plus end idle-duration)])
            (when user-ids
              [:in :user-id [:lift user-ids]])]
    :order-by [:created]}))

(defn get-project-intervals
  [connectable project-id
   & {:keys [^LocalDateTime start ^LocalDateTime end user-ids]}]
  (->> (get-project-events connectable project-id :start start :end end
                           :user-ids user-ids)
       (group-by :reviewer-event/user-id)
       (medley/map-vals
        (fn [events]
          (let [start (or start LocalDateTime/MIN)
                end (or end LocalDateTime/MAX)]
            (->> events to-intervals
                 (keep
                  (fn [itvl]
                    (when-not (or (.isAfter start (:end itvl))
                                  (.isBefore end (:start itvl)))
                      (-> itvl
                          (update :start #(if (.isBefore ^LocalDateTime % start) start %))
                          (update :end #(if (.isAfter ^LocalDateTime % end) end %))))))))))))

(defn ->epoch-millis [^LocalDateTime ldt]
  (-> (.toEpochSecond ldt (ZoneOffset/UTC))
      (* 1000)
      (+ (quot (.getNano ldt) 1000000))))

(defn get-selection-path [context path]
  (loop [selections (-> context
                        executor/selection
                        selection/selections)
         [k & more] path]
    (let [v (some #(when (= k (selection/field-name %)) %) selections)]
      (cond
        (empty? v) nil
        (empty? more) v
        :else (recur (selection/selections v) more)))))

(defn Project-reviewerTime [context {:keys [end start reviewerIds]} {:keys [id]}]
  (let [conn (:datasource @db/*active-db*)
        intervals-map (get-project-intervals
                       conn id
                       :end (when end (->LocalDateTime end))
                       :start (when start (->LocalDateTime start))
                       :user-ids reviewerIds)
        intervals (mapcat (fn [[k v]] (map #(assoc % :user-id k) v)) intervals-map)
        usernames (when (get-selection-path context [:intervals :reviewer :name])
                    (->> (db/execute!
                          conn
                          {:select [:user-id :username] :from :web-user
                           :where [:in :user-id [:lift (keys intervals-map)]]})
                         (map (juxt :web-user/user-id :web-user/username))
                         (into {})))]
    {:intervals (map
                 (fn [{:keys [article-id end start user-id]}]
                   {:article {:id article-id}
                    :end (->epoch-millis end)
                    :reviewer {:id user-id
                               :name (get usernames user-id)}
                    :start (->epoch-millis start)})
                 intervals)
     :totalSeconds (-> intervals intervals->total-duration
                       .toMillis (quot 1000))}))
