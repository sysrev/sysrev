(ns sysrev.base
  (:require [goog.array :as garray]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [clojure.string :as str]
            [pushy.core :as pushy]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$]]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [secretary.core :as secretary :refer-macros [defroute]]
            [sysrev.shared.util :as sutil]))

(defonce sysrev-hostname "sysrev.com")
(defonce sysrev-blog-hostname "blog.sysrev.com")

(def debug? ^boolean js/goog.DEBUG)

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
    (set! js/console.log
          (fn []
            (let [args (js-arguments)]
              (.apply (.-defaultLog js/console) js/console (garray/clone args))
              (swap! console-logs update :log
                     #(conj (or % []) (vec (js->clj args))))
              nil)))
    ;; wrap js/console.error
    (let [default (.bind js/console.error js/console)]
      (set! js/console.defaultError default)
      (swap! default-console-fns assoc :error default))
    (set! js/console.error
          (fn []
            (let [args (js-arguments)]
              (.apply (.-defaultError js/console) js/console (garray/clone args))
              (swap! console-logs update :error
                     #(conj (or % []) (vec (js->clj args))))
              nil)))
    nil))

(defn log-entry-string [entry]
  (let [[primary & other] entry]
    (if (and (empty? other)
             (vector? primary)
             (= 2 (count primary)) )
      (str "[" (first primary) "] " (second primary))
      (pr-str entry))))

(defn ^:export get-console-logs []
  (with-out-str
    (doseq [entry (:log @console-logs)]
      (println (log-entry-string entry)))))

(defn ^:export get-console-errors []
  (with-out-str
    (doseq [entry (:error @console-logs)]
      (println (log-entry-string entry)))))

(defn app-id []
  (cond (-> js/document
            (.getElementById "blog-app"))  :blog
        :else                              :main))

(reg-sub :app-id (fn [_] (app-id)))

(reg-event-db
 :toggle-analytics
 [trim-v]
 (fn [db [enable?]]
   (assoc db :disable-analytics (not enable?))))

(defn ^:export toggle-analytics [enable?]
  (dispatch [:toggle-analytics enable?]))

(defn run-analytics? []
  (and (aget js/window "ga")
       (or (= js/window.location.host sysrev-hostname)
           (= js/window.location.host sysrev-blog-hostname))
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
                 (ga "set" "location" (str js/window.location.origin))
                 (ga "set" "page" (str route))
                 (when @user-uuid
                   (ga "set" "userId" (str @user-uuid)))
                 (ga "send" "pageview")))
             (reset! active-route url)
             url)
         (do (pushy/replace-token! history "/")
             nil))))))

(def default-db {})
