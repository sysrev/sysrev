(ns sysrev.base
  (:require [goog.array :as garray]
            [clojure.string :as str]
            [pushy.core :as pushy]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-event-db trim-v
                                   set-loggers!]]
            [re-frame.db :refer [app-db]]
            [secretary.core :as secretary]))

(defonce sysrev-hostname "sysrev.com")

(def debug? ^boolean js/goog.DEBUG)

(defonce tests-running (r/atom nil))

(defonce default-console-fns (atom {}))

(defonce console-logs (atom {}))

;;; based on
;;; https://stackoverflow.com/questions/19846078/how-to-read-from-chromes-console-in-javascript
;;; and
;;; cljs.core/enable-console-print!
(defn setup-console-hooks []
  (when (empty? @default-console-fns)
    ;; wrap js/console.log
    (let [default (.bind js/console.log js/console)]
      (set! js/console.defaultLog default)
      (swap! default-console-fns assoc :log default))
    ;; wrap js/console.warn
    (let [default (.bind js/console.warn js/console)]
      (set! js/console.defaultWarn default)
      (swap! default-console-fns assoc :warn default))
    ;; wrap js/console.error
    (let [default (.bind js/console.error js/console)]
      (set! js/console.defaultError default)
      (swap! default-console-fns assoc :error default))
    ;; redefine console functions
    (letfn [(make-console-fn [msg-type default-fn & {:keys [ignore-regexps]}]
              (fn []
                (let [args (js-arguments)]
                  (.apply default-fn js/console (garray/clone args))
                  (when-not (some #(re-find % (-> (js->clj args) vec pr-str))
                                  ignore-regexps)
                    (swap! console-logs update msg-type
                           #(conj (or % [])
                                  {:data (vec (js->clj args))
                                   :uri js/window.location.href})))
                  nil)))]
      (set! js/console.log
            (make-console-fn :log js/console.defaultLog))
      (set! js/console.warn
            (make-console-fn :warn js/console.defaultWarn
                             :ignore-regexps
                             [#"(?i)you may test.*stripe.*integration"]))
      (set! js/console.error
            (make-console-fn :error js/console.defaultError
                             :ignore-regexps
                             [#"(?i)no longer attached.*unable to animate"
                              #"(?i)taoensso\.sente.*WebSocket error"]))

      ;; re-frame grabs the console.log fns on import, so we have to reset them
      (set-loggers! {:debug js/console.debug
                     :error js/console.error
                     :group js/console.group
                     :groupEnd js/console.groupEnd
                     :log js/console.log
                     :warn js/console.warn}))))

(defn log-entry-string [entry]
  (let [[primary & other] entry]
    (if (and (empty? other)
             (vector? primary)
             (= 2 (count primary)))
      (str "[" (first primary) "] " (second primary))
      (pr-str entry))))

(defn ^:export get-console-logs []
  (with-out-str
    (doseq [entry (:log @console-logs)]
      (println (log-entry-string entry)))))

(defn ^:export get-console-warnings []
  (with-out-str
    (doseq [entry (:warn @console-logs)]
      (println (log-entry-string entry)))))

(defn ^:export get-console-errors []
  (with-out-str
    (doseq [entry (:error @console-logs)]
      (println (log-entry-string entry)))))

(reg-event-db :toggle-analytics [trim-v]
              (fn [db [enable?]]
                (assoc db :disable-analytics (not enable?))))

(defn ^:export toggle-analytics [enable?]
  (dispatch [:toggle-analytics enable?]))

(defn run-analytics? []
  (and (aget js/window "ga")
       (= js/window.location.host sysrev-hostname)
       (not (:disable-analytics @app-db))))

(defn ga
  "google analytics (function loaded from ga.js)"
  [& more]
  (when (run-analytics?)
    (.. (aget js/window "ga")
        (apply nil (clj->js more)))))

(defn ga-event
  "Send a Google Analytics event."
  [category action & [label]]
  (let [user-uuid (subscribe [:user/uuid])]
    (ga "set" "userId" (str @user-uuid))
    (ga "send" "event" category action label)))

(secretary/set-config! :prefix "")

(defonce active-route (r/atom nil))

(defonce history
  (pushy/pushy
   secretary/dispatch!
   (fn [url]
     (let [route (first (str/split url #"\?"))]
       (if (secretary/locate-route route)
         (do (when (not= url @active-route)
               (let [user-uuid (subscribe [:user/uuid])]
                 (ga "set" "location" (str js/document.location))
                 (ga "set" "page" (str route))
                 (when @user-uuid
                   (ga "set" "userId" (str @user-uuid)))
                 (ga "send" "pageview")))
             (reset! active-route url)
             url)
         (do (pushy/replace-token! history "/")
             nil))))))

(def default-db {})
