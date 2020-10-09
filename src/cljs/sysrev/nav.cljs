(ns sysrev.nav
  (:require [pushy.core :as pushy]
            [re-frame.core :refer [reg-event-fx reg-fx]]
            [cljs-http.client :as http]
            [sysrev.base :refer [history]]
            [sysrev.shared.text :refer [uri-title]]
            [sysrev.util :refer [scroll-top]]))

(defn make-url [url & [params]]
  (let [params-str (not-empty (http/generate-query-string params))]
    (cond-> url
      params-str (str "?" params-str))))

(defn current-url-base []
  (str js/window.location.protocol "//" js/window.location.host))

(defn load-url [url & {:keys [absolute] :or {absolute false}}]
  (set! js/window.location.href
        (cond->> url
          (not absolute) (str (current-url-base)))))

(reg-fx :load-url (fn [[url & {:keys [absolute] :or {absolute false}}]]
                    (load-url url :absolute absolute)))

(reg-event-fx :load-url (fn [_ [_ url & {:keys [absolute] :or {absolute false}}]]
                          {:load-url [url :absolute absolute]}))

(defn nav
  "Change the current url. If `redirect` is true, replace current entry in
  HTML5 history stack. If `top` is true (default), scroll to page top."
  [url & {:keys [params top redirect]
          :or {top true redirect false}}]
  ((if redirect pushy/replace-token! pushy/set-token!)
   history (make-url url params))
  (when top (scroll-top))
  nil)

(reg-fx :nav (fn [[url & {:keys [params top redirect]
                          :or {top true redirect false}}]]
               (nav url :params params :top top :redirect redirect)))

(reg-event-fx :nav (fn [_ [_ url & {:keys [params top redirect]
                                    :or {top true redirect false}}]]
                     {:nav [url :params params :top top :redirect redirect]}))

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

(defn set-page-title [title]
  (let [uri js/window.location.pathname]
    (set! (-> js/document .-title)
          (if (string? title) title (uri-title uri))
          #_ (cond-> "Sysrev"
               (string? title) (str " - " title)))))

(reg-fx :set-page-title set-page-title)
