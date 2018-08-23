(ns sysrev.nav
  (:require [clojure.string :as str]
            [secretary.core :as secretary]
            [pushy.core :as pushy]
            [re-frame.core :refer [reg-event-db reg-fx]]
            [cljs-http.client :as hc]
            [sysrev.base :refer [history]]
            [sysrev.util :refer [scroll-top]]
            [sysrev.shared.util :refer [map-values]]))

(defn force-dispatch [uri]
  (secretary/dispatch! uri))

(defn make-url [route & [params]]
  (let [params-map
        (if (map? params) params
            (->> params
                 (group-by #(-> % keys first))
                 (map-values
                  #(->> % (map vals) (apply concat)))))
        hash (hc/generate-query-string params-map)]
    (if (empty? hash)
      route
      (str route "?" hash))))

(defn current-url-base []
  (str js/window.location.protocol
       "//"
       js/window.location.host))

(defn nav-reload [route]
  (set! js/window.location.href
        (str (current-url-base) route)))

(reg-fx :nav-reload #(when % (nav-reload %)))

(defn nav
  "Change the current route."
  [route & {:keys [params]}]
  (pushy/set-token! history (make-url route params)))

(defn nav-redirect
  "Change the current route and remove from HTML5 history stack."
  [route & {:keys [params scroll-top?]}]
  (pushy/replace-token! history (make-url route params))
  (when scroll-top?
    (scroll-top)))

(defn nav-scroll-top
  "Change the current route then scroll to top of page."
  [route & {:keys [params]}]
  (pushy/set-token! history (make-url route params))
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
 :nav-redirect
 (fn [url] (nav-redirect url)))

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

(defn get-url-params []
  (let [s js/window.location.search
        query (if (and (string? s)
                       (str/starts-with? s "?"))
                (subs s 1)
                s)]
    (hc/parse-query-params query)))

(reg-fx
 :set-page-title
 (fn [title]
   (set! (-> js/document .-title)
         (if (string? title)
           (str "SysRev - " title)
           "SysRev"))))
