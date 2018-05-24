(ns sysrev.nav
  (:require [secretary.core :as secretary]
            [pushy.core :as pushy]
            [re-frame.core :refer [reg-event-db reg-fx]]
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

(reg-fx
 :nav
 (fn [url] (nav url)))

(reg-fx
 :nav-scroll-top
 (fn [url] (nav-scroll-top url)))

(reg-fx
 :scroll-top
 (fn [_] (scroll-top)))

(defn- reload-page []
  (-> js/window .-location (.reload true)))

(reg-fx
 :reload-page
 (fn [[reload? delay-ms]]
   (when reload?
     (if delay-ms
       (js/setTimeout #(reload-page) delay-ms)
       (reload-page)))))

(defn ^:export set-token [path]
  (pushy/set-token! history path))

(reg-fx
 :set-page-title
 (fn [title]
   (set! (-> js/document .-title)
         (if (string? title)
           (str "SysRev - " title)
           "SysRev"))))
