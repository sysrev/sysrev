(ns sysrev.nav
  (:require [goog.uri.utils :as uri-utils]
            [pushy.core :as pushy]
            [re-frame.core :refer [reg-event-db reg-event-fx reg-fx]]
            [cljs-http.client :as http]
            [sysrev.base :refer [history]]
            [sysrev.shared.text :refer [uri-title]]
            [sysrev.util :refer [scroll-top]]))

(defn make-url [route & [params]]
  (let [hash (http/generate-query-string params)]
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

(defn nav
  "Change the current route."
  [route & {:keys [params]}]
  (scroll-top)
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

(reg-fx :nav-scroll-top (fn [url]
                          (let [uri (uri-utils/getPath url)
                                params (-> (uri-utils/getQueryData url)
                                           (http/parse-query-params))]
                            (nav-scroll-top uri :params params))))

(reg-fx :nav-reload (fn [url] (nav-reload url)))

(reg-fx :scroll-top (fn [_] (scroll-top)))

(reg-event-fx :nav-redirect (fn [_ [_ url]] {:nav-redirect url}))

(reg-event-fx :nav-scroll-top (fn [_ [_ url]] {:nav-scroll-top url}))

(reg-event-fx :nav-reload (fn [_ [_ url]] {:nav-reload url}))

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

(defn set-page-title [title]
  (let [uri js/window.location.pathname]
  (set! (-> js/document .-title)
        (if (string? title) title (uri-title uri)))))

(reg-fx :set-page-title set-page-title)
