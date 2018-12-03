(ns sysrev.base
  (:require [pushy.core :as pushy]
            [secretary.core :as secretary]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [clojure.string :as str]
            [reagent.core :as r])
  (:require-macros [secretary.core :refer [defroute]]))

(defonce sysrev-hostname "sysrev.com")
(defonce sysrev-blog-hostname "blog.sysrev.com")

(def debug?
  ^boolean js/goog.DEBUG)

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

(def use-new-article-list? true)
