(ns sysrev.base
  (:require [goog.array :as garray]
            [clojure.string :as str]
            [pushy.core :as pushy]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch set-loggers!]]
            [secretary.core :as secretary]))

(defonce sysrev-hostname "sysrev.com")

(def debug? ^boolean js/goog.DEBUG)

(defonce tests-running (r/atom nil))

(defonce default-console-fns (atom {}))

(defonce console-logs (atom {}))

(defonce show-blog-links (atom nil))

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
                             [#"(?i)you may test.*stripe.*integration"
                              #"Ignoring Event: localhost"]))
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

(secretary/set-config! :prefix "")

(defonce active-route (r/atom nil))

(defonce history
  (pushy/pushy
   secretary/dispatch!
   (fn [url]
     (let [route (first (str/split url #"\?"))]
       (if (secretary/locate-route route)
         (do (reset! active-route url)
             url)
         (do (pushy/replace-token! history "/")
             nil))))))

(def default-db {})
