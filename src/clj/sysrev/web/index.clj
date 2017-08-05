(ns sysrev.web.index
  (:require [hiccup.page :as page]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.config.core :refer [env]]
            [sysrev.resources :as res]
            [sysrev.db.users :as users]))

(defonce web-asset-path (atom "/out"))

(defn set-web-asset-path [& [path]]
  (let [path (or path "/out")]
    (reset! web-asset-path path)))

(defn- user-theme [request]
  (let [{{{:keys [user-id] :as identity} :identity
          :as session} :session} request]
    (or (some-> user-id (users/get-user-by-id) :settings :ui-theme)
        "default")))

(defn css-paths [& {:keys [theme] :or {theme "default"}}]
  [(format "/semantic/%s/semantic.min.css" theme)
   (format "/css/style.%s.css" theme)
   "https://fonts.googleapis.com/css?family=Open+Sans"])

(defn index [& [request]]
  (page/html5
   [:head
    [:title "SysRev (re-frame)"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (apply page/include-css (css-paths :theme (user-theme request)))
    (page/include-js "/ga.js")]
   [:body
    [:div {:id "app"} loading-content]
    (let [js-name (if (= (:profile env) :prod)
                    (str "sysrev-" res/build-id ".js")
                    "sysrev.js")]
      (page/include-js (str @web-asset-path "/" js-name)))]))

(defn not-found [& [request]]
  (page/html5
   [:head
    [:title "SysRev (re-frame)"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (apply page/include-css (css-paths :theme (user-theme request)))]
   [:body
    [:div {:id "app"}
     [:div.ui.container
      [:div.ui.stripe {:style "padding-top: 20px;"}
       [:h1.ui.header.huge.center.aligned
        "Not found"]]]]]))
