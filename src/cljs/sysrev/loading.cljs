(ns sysrev.loading
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer-macros [defn-spec]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-fx]]
            [sysrev.util :as util :refer [now-ms]]
            [sysrev.shared.util :as sutil :refer [in? opt-keys apply-keyargs]]))

(defonce ^:private ajax-db (r/atom {}))

(s/def ::item-name keyword?)
(s/def ::item (s/and vector?, #(pos? (count %)), #(s/valid? ::item-name (first %))))

(s/def ::only ::item-name)
(s/def ::ignore (s/coll-of ::item-name))

(s/def ::time-ms int?)

;;;
;;; Maintain counters for start/completion of AJAX requests (data)
;;;

(defonce ^:private ajax-data-counts (r/cursor ajax-db [:ajax :data]))

(defn- data-sent-count [item]
  (r/cursor ajax-data-counts [:sent item]))

(defn- data-returned-count [item]
  (r/cursor ajax-data-counts [:returned item]))

(defn-spec item-loading? boolean?
  "Tests if an AJAX data request for `item` is currently pending."
  [item ::item]
  (> (or @(data-sent-count item) 0)
     (or @(data-returned-count item) 0)))

(defn- any-pending-impl
  "Tests for pending AJAX requests based on history from `counts`."
  [counts & {:keys [only ignore]}]
  (->>
   ;; get sequence of all requested items
   (keys (get-in counts [:sent]))
   ;; ignore items except where name equals `only` (if specified)
   (filter #(or (nil? only) (= (first %) only)))
   ;; ignore items where name is present in `ignore` (if specified)
   (filter #(not (in? ignore (first %))))
   ;; test if any item has more requests started than finished
   (some #(> (get-in counts [:sent %] 0)
             (get-in counts [:returned %] 0)))
   boolean))

#_(defn-spec any-loading? boolean?
  "Tests if any AJAX data request is currently pending."
  [& {:keys [only ignore] :as opts} (opt-keys ::only ::ignore)]
  (apply-keyargs any-pending-impl @ajax-data-counts opts))

(defn any-loading?
  [& {:keys [only ignore] :as opts}]
  (apply-keyargs any-pending-impl @ajax-data-counts opts))

(defn-spec item-failed? boolean?
  "Tests if most recent AJAX data request for `item` returned an
  error status."
  [item ::item]
  (true? (get-in @ajax-data-counts [:failed item])))

(defn- get-spam-time
  "Returns time (ms) of `n`th-most recent fetch of `item`."
  [item n]
  (first (drop n (get-in @ajax-data-counts [:timings item]))))

(defn-spec item-spammed? boolean?
  "Checks if `item` has been fetched repeatedly too many times in a
  short duration (protection against infinite AJAX request loop)."
  [item ::item]
  (let [[max-iter duration] [6 600]
        spam-ms (get-spam-time item max-iter)]
    (boolean (and spam-ms (-> (- (now-ms) spam-ms) (< duration))))))

(defn- data-failed
  "Sets state of `item` as failed."
  [item]
  (swap! ajax-data-counts assoc-in [:failed item] true)
  (dispatch [:data/reset-required]))
(reg-fx :data-failed data-failed)

(defn- reset-data-failed
  "Resets failed state for `item`."
  [item]
  (swap! ajax-data-counts assoc-in [:failed item] false))
(reg-fx :reset-data-failed reset-data-failed)

;;;
;;; Maintain counters for start/completion of AJAX requests (action)
;;;

(defonce ^:private ajax-action-counts (r/cursor ajax-db [:ajax :action]))

(defn- action-sent-count [item]
  (r/cursor ajax-action-counts [:sent item]))

(defn- action-returned-count [item]
  (r/cursor ajax-action-counts [:returned item]))

(defn-spec action-running? boolean?
  "Tests if an AJAX action request for `item` is currently pending."
  [item ::item]
  (> (or @(action-sent-count item) 0)
     (or @(action-returned-count item) 0)))

#_(defn-spec any-action-running? boolean?
  "Tests if any AJAX action request is currently pending."
  [& {:keys [only ignore] :as opts} (opt-keys ::only ::ignore)]
  (apply-keyargs any-pending-impl @ajax-action-counts opts))

(defn any-action-running?
  "Tests if any AJAX action request is currently pending."
  [& {:keys [only ignore] :as opts}]
  (apply-keyargs any-pending-impl @ajax-action-counts opts))



;;;
;;; Loading indicator
;;;

(def ignore-data-names [:article/annotations
                        :project/sources
                        :project/important-terms
                        :pdf/open-access-available?])

(def ignore-action-names [:sources/delete])

(defn- any-data-for-indicator? []
  (any-loading? :ignore ignore-data-names))

(defn- any-action-for-indicator? []
  (any-action-running? :ignore ignore-action-names))

(defn- any-loading-for-indicator?
  "Tests for any pending AJAX requests that should trigger display of
  global loading indicator in header (excludes some specific requests)."
  []
  (or (any-data-for-indicator?)
      (any-action-for-indicator?)))

(defn- update-loading-status
  "Updates timing state for AJAX request activity and display state of
  global loading indicator."
  []
  (let [db @ajax-db
        active? (any-loading-for-indicator?)
        action? (any-action-for-indicator?)
        time-now (now-ms)
        time-active       (if active? time-now
                              (get-in db [:ajax :time-active] 0))
        time-inactive     (if (not active?) time-now
                              (get-in db [:ajax :time-inactive] 0))
        action-active     (if action? time-now
                              (get-in db [:ajax :action-active] 0))
        action-inactive   (if (not action?) time-now
                              (get-in db [:ajax :action-inactive] 0))
        cur-status (get-in db [:ajax :loading-status] true)
        new-status (cond
                     ;; enable indicator when ajax active for >= duration
                     (>= (- time-active time-inactive) 75) true
                     ;; disable indicator when ajax inactive for >= duration
                     (>= (- time-inactive time-active) 175) false
                     ;; otherwise maintain current display status
                     :else cur-status)]
    (swap! ajax-db (fn [db]
                     (-> db
                         ;; update logged time for ajax activity
                         (cond-> active?         (assoc-in [:ajax :time-active] time-now))
                         ;; update logged time for ajax inactivity
                         (cond-> (not active?)   (assoc-in [:ajax :time-inactive] time-now))
                         ;; update logged time for ajax action activity
                         (cond-> action?         (assoc-in [:ajax :action-active] time-now))
                         ;; update logged time for ajax action inactivity
                         (cond-> (not action?)   (assoc-in [:ajax :action-inactive] time-now))
                         ;; update indicator display status
                         (assoc-in [:ajax :loading-status] new-status))))))

(defn- schedule-loading-update
  "Updates AJAX loading state immediately and then several times soon
  (needed to support triggering show/hide of global loading indicator
  while a request is pending or shortly after it has finished)."
  []
  (update-loading-status)
  (doseq [ms (map #(+ 5 (* 37.5 (inc %))) (range 6))]
    (js/setTimeout update-loading-status ms)))

(defonce loading-indicator (r/cursor ajax-db [:ajax :loading-status]))

;;;
;;; AJAX timing status functions
;;;

(defn-spec ^:export ajax-status ::time-ms
  "Returns duration (ms) for which AJAX requests globally have been
   continuously active or inactive. Supports allowing for time to
   elapse before some action is permitted by inactive AJAX state."
  []
  (update-loading-status)
  (let [db @ajax-db]
    (- (get-in db [:ajax :time-active])
       (get-in db [:ajax :time-inactive]))))

(defn-spec ^:export ajax-action-status ::time-ms
  "Returns duration (ms) for which AJAX action requests globally have
   been continuously active or inactive (ignores data requests)."
  []
  (update-loading-status)
  (let [db @ajax-db]
    (- (get-in db [:ajax :action-active])
       (get-in db [:ajax :action-inactive]))))

(defn ajax-status-inactive?
  "Tests if AJAX requests have been inactive continuously for `duration`
  ms (default 30)."
  [& [duration]]
  (< (ajax-status) (- (or duration 30))))

(defn ajax-action-inactive?
  "Tests if AJAX action requests have been inactive continuously for
  `duration` ms (default 30) (ignores data requests)."
  [& [duration]]
  (< (ajax-action-status) (- (or duration 30))))

;;;
;;; Events for start/completion of AJAX requests
;;;

(defn-spec ^:private data-sent any?
  "Event handler upon sending an AJAX data request."
  [item ::item]
  (let [time-now (now-ms)
        loading? (any-loading-for-indicator?)]
    (swap! ajax-db (fn [db]
                     (-> (update-in db [:ajax :data :sent item] #(inc (or % 0)))
                         (update-in [:ajax :data :timings item] #(take 10 (concat [time-now] %)))
                         (assoc-in [:ajax :time-active] time-now)
                         (cond-> (not loading?) (assoc-in [:ajax :time-inactive] time-now)))))
    (schedule-loading-update)))
(reg-fx :data-sent data-sent)

(defn-spec ^:private data-returned any?
  "Event handler upon receiving response to an AJAX data request."
  [item ::item]
  (swap! ajax-db update-in [:ajax :data :returned item] #(inc (or % 0)))
  (schedule-loading-update))
(reg-fx :data-returned data-returned)

(defn-spec ^:private action-sent any?
  "Event handler upon sending an AJAX action request."
  [item ::item]
  (let [time-now (now-ms)
        loading? (any-loading-for-indicator?)]
    (swap! ajax-db (fn [db]
                     (-> db
                         (update-in [:ajax :action :sent item] #(inc (or % 0)))
                         (assoc-in [:ajax :time-active] time-now)
                         (cond-> (not loading?) (assoc-in [:ajax :time-inactive] time-now)))))))
(reg-fx :action-sent action-sent)

(defn-spec ^:private action-returned any?
  "Event handler upon receiving response to an AJAX action request."
  [item ::item]
  (swap! ajax-db update-in [:ajax :action :returned item] #(inc (or % 0)))
  (schedule-loading-update))
(reg-fx :action-returned action-returned)
