(ns sysrev.loading
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-fx]]
            [sysrev.shared.util :refer [in?]]))

(defonce ajax-db (r/atom {}))

;;;
;;; Maintain counters for start/completion of AJAX requests (data)
;;;

(defn ajax-data-counts []
  (r/cursor ajax-db [:ajax :data]))

(defn- data-sent-count [item]
  (r/cursor (ajax-data-counts) [:sent item]))

(defn- data-returned-count [item]
  (r/cursor (ajax-data-counts) [:returned item]))

;; Tests if an AJAX request for `item` is currently pending
(defn item-loading? [item]
  (> (or @(data-sent-count item) 0)
     (or @(data-returned-count item) 0)))

(defn- any-loading-impl
  [counts & [filter-item-name ignore-item-names]]
  (boolean
   (->> (keys (get-in @counts [:sent]))
        (filter #(or (nil? filter-item-name)
                     (= (first %) filter-item-name)))
        (filter #(not (in? ignore-item-names (first %))))
        (some #(> (get-in @counts [:sent %] 0)
                  (get-in @counts [:returned %] 0))))))

(defn any-loading?
  [& {:keys [only ignore]}]
  (let [counts (ajax-data-counts)]
    (any-loading-impl counts only ignore)))

(defn item-failed? [item]
  (true? (get-in @(ajax-data-counts) [:failed item])))

;; Returns the time (ms) of 4th most recent fetch of item
(defn- get-spam-time [item]
  (->> (get-in @(ajax-data-counts) [:timings item])
       (drop 4)
       first))

;; Checks if item has been fetched 5 times within last 2.5s
(defn item-spammed? [item]
  (let [spam-ms (get-spam-time item)]
    (if (nil? spam-ms)
      false
      (let [now-ms (js/Date.now)]
        (< (- now-ms spam-ms) 2500)))))

(defn data-failed [item]
  (swap! (ajax-data-counts) assoc-in [:failed item] true)
  (dispatch [:data/reset-required]))

(reg-fx :data-failed (fn [item] (data-failed item)))

(defn reset-data-failed [item]
  (swap! (ajax-data-counts) assoc-in [:failed item] false))

(reg-fx :reset-data-failed (fn [item] (reset-data-failed item)))

;;;
;;; Maintain counters for start/completion of AJAX requests (action)
;;;

(defn ajax-action-counts []
  (r/cursor ajax-db [:ajax :action]))

(defn action-sent-count [item]
  (r/cursor (ajax-action-counts) [:sent item]))

(defn action-returned-count [item]
  (r/cursor (ajax-action-counts) [:returned item]))

(defn action-running? [item]
  (> (or @(action-sent-count item) 0)
     (or @(action-returned-count item) 0)))

(defn- any-running-impl
  [counts & [filter-item-name ignore-item-names]]
  (boolean
   (->> (keys (get-in @counts [:sent]))
        (filter #(or (nil? filter-item-name)
                     (= (first %) filter-item-name)))
        (filter #(not (in? ignore-item-names (first %))))
        (some #(> (get-in @counts [:sent %] 0)
                  (get-in @counts [:returned %] 0))))))

(defn any-action-running?
  [& {:keys [only ignore]}]
  (let [counts (ajax-action-counts)]
    (any-running-impl counts only ignore)))

;;;
;;; Loading indicator
;;;

(def ignore-data-names [:article/annotations
                        :project/sources
                        :project/important-terms
                        :pdf/open-access-available?])
(def ignore-action-names [:sources/delete])

(defn any-loading-for-indicator? []
  (or (any-loading? :ignore ignore-data-names)
      (any-action-running? :ignore ignore-action-names)))

(defn update-loading-status []
  (let [db @ajax-db
        active? (any-loading-for-indicator?)
        time-now (js/Date.now)
        time-inactive (if (not active?) time-now
                          (get-in db [:ajax :time-inactive] 0))
        time-active (if active? time-now
                        (get-in db [:ajax :time-active] 0))
        cur-status (get-in db [:ajax :loading-status] true)
        new-status
        (cond
          ;; enable indicator when ajax active for >= 75ms
          (>= (- time-active time-inactive) 75) true
          ;; disable indicator when ajax inactive for >= 200ms
          (>= (- time-inactive time-active) 200) false
          ;; otherwise maintain existing indicator status
          :else cur-status)]
    (swap! ajax-db (fn [db]
                     (-> db
                         ;; update logged time for ajax activity
                         (update-in [:ajax :time-active]
                                    #(if active? time-now %))
                         ;; update logged time for ajax inactivity
                         (update-in [:ajax :time-inactive]
                                    #(if (not active?) time-now %))
                         ;; update indicator status
                         (assoc-in [:ajax :loading-status] new-status))))))

(defn schedule-loading-update [& [times]]
  (let [times (if (vector? times) times
                  [5 25 50 75 100 150 200 275 350 500])]
    (doseq [ms times]
      (js/setTimeout #(update-loading-status) ms))))

(defn loading-indicator []
  (r/cursor ajax-db [:ajax :loading-status]))

;;;
;;; Events for start/completion of AJAX requests
;;;

(defn data-sent [item]
  (let [time-ms (js/Date.now)
        loading? (any-loading-for-indicator?)]
    (swap! ajax-db (fn [db]
                     (-> db
                         (update-in [:ajax :data :sent item]
                                    #(-> % (or 0) inc))
                         (update-in [:ajax :data :timings item]
                                    #(->> (concat [time-ms] %)
                                          (take 10)))
                         (assoc-in [:ajax :time-active] time-ms)
                         (update-in [:ajax :time-inactive]
                                    #(if (not loading?) time-ms %)))))
    (schedule-loading-update)))

(reg-fx :data-sent (fn [item] (data-sent item)))

(defn data-returned [item]
  (swap! ajax-db update-in
         [:ajax :data :returned item]
         #(-> % (or 0) inc))
  (schedule-loading-update))

(reg-fx :data-returned (fn [item] (data-returned item)))

(defn action-sent [item]
  (let [time-ms (js/Date.now)
        loading? (any-loading-for-indicator?)]
    (swap! ajax-db (fn [db]
                     (-> db
                         (update-in [:ajax :action :sent item]
                                    #(-> % (or 0) inc))
                         (assoc-in [:ajax :time-active] time-ms)
                         (update-in [:ajax :time-inactive]
                                    #(if (not loading?) time-ms %)))))))

(reg-fx :action-sent (fn [item] (action-sent item)))

(defn action-returned [item]
  (swap! ajax-db update-in
         [:ajax :action :returned item]
         #(-> % (or 0) inc))
  (schedule-loading-update))

(reg-fx :action-returned (fn [item] (action-returned item)))
