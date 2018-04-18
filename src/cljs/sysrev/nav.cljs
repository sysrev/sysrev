(ns sysrev.nav
  (:require [secretary.core :as secretary]
            [pushy.core :as pushy]
            [re-frame.core :refer [reg-event-db]]
            [sysrev.base :refer [history]]
            [sysrev.util :refer [scroll-top]]))

(defn force-dispatch [uri]
  (secretary/dispatch! uri))

(defn nav
  "Change the current route."
  [route]
  (pushy/set-token! history route))

(defn nav-scroll-top
  "Change the current route then scroll to top of page."
  [route]
  (pushy/set-token! history route)
  (scroll-top))

(reg-event-db
 :schedule-scroll-top
 (fn [db]
   (assoc db :scroll-top true)))

(reg-event-db
 :scroll-top
 (fn [db]
   (scroll-top)
   (dissoc db :scroll-top)))
