(ns sysrev.nav
  (:require [clojure.string :as str]
            [secretary.core :as secretary]
            [pushy.core :as pushy]
            [re-frame.core :refer [reg-event-db reg-event-fx reg-fx]]
            [cljs-http.client :as hc]
            [cognitect.transit :as transit]
            [sysrev.base :refer [history]]
            [sysrev.util :refer [scroll-top]]))

(defn force-dispatch [uri]
  (secretary/dispatch! uri))

(defn make-url [route & [params]]
  (let [hash (hc/generate-query-string params)]
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

(defn load-url [url]
  (set! js/window.location.href url))

(reg-fx :nav-reload #(when % (nav-reload %)))

(defn nav
  "Change the current route."
  [route & {:keys [params]}]
  (pushy/set-token! history (make-url route params))
  nil)

(defn nav-redirect
  "Change the current route and replace its entry in HTML5 history stack."
  [route & {:keys [params scroll-top?]}]
  (pushy/replace-token! history (make-url route params))
  (when scroll-top? (scroll-top)))

(defn nav-scroll-top
  "Change the current route then scroll to top of page."
  [route & {:keys [params]}]
  (pushy/set-token! history (make-url route params))
  (scroll-top))

(reg-event-db :schedule-scroll-top #(assoc % :scroll-top true))

(reg-event-db :scroll-top #(do (scroll-top) (dissoc % :scroll-top)))

(reg-fx :nav (fn [url] (nav url)))

(reg-fx :nav-redirect (fn [url] (nav-redirect url)))

(reg-fx :nav-scroll-top (fn [url] (nav-scroll-top url)))

(reg-fx :scroll-top (fn [_] (scroll-top)))

(reg-event-fx :nav-redirect (fn [_ [_ url]] {:nav-redirect url}))

(defn- reload-page []
  (-> js/window .-location (.reload true)))

(reg-fx :reload-page
        (fn [[reload? delay-ms]]
          (when reload?
            (if delay-ms
              (js/setTimeout #(reload-page) delay-ms)
              (reload-page)))))

(defn ^:export set-token [path]
  (pushy/set-token! history path))

#_
(defn ^:export eval-form [form-transit]
  (let [form ]))

(defn get-url-params []
  (let [s js/window.location.search
        query (if (and (string? s)
                       (str/starts-with? s "?"))
                (subs s 1)
                s)]
    (hc/parse-query-params query)))

(defn set-page-title [title]
  (set! (-> js/document .-title)
        (if (string? title)
          (str "Sysrev - " title)
          "Sysrev")))

(reg-fx :set-page-title set-page-title)
