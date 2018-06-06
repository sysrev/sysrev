(ns sysrev.web.index
  (:require [hiccup.page :as page]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.config.core :refer [env]]
            [sysrev.stripe :refer [stripe-public-key]]
            [sysrev.resources :as res]
            [sysrev.db.users :as users]
            [clojure.string :as str]))

(defonce web-asset-path (atom "/out"))

(defonce lucky-orange-enabled (atom (= (:profile env) :prod)))

(defn set-web-asset-path [& [path]]
  (let [path (or path "/out")]
    (reset! web-asset-path path)))

(defn- user-theme [request]
  (let [{{{:keys [user-id] :as identity} :identity
          :as session} :session} request]
    (if user-id
      (or (some-> user-id (users/get-user-by-id) :settings :ui-theme
                  str/lower-case)
          "default")
      (or (some-> session :settings :ui-theme
                  str/lower-case)
          "default"))))

(defn css-paths [& {:keys [theme] :or {theme "default"}}]
  [(format "/semantic/%s/semantic.min.css" theme)
   (format "/css/style.%s.css" theme)
   "https://fonts.googleapis.com/css?family=Open+Sans"])

(defn favicon-headers []
  (list [:link {:rel "apple-touch-icon"
                :sizes "180x180"
                :href "/apple-touch-icon.png"}]
        [:link {:rel "icon"
                :type "image/png"
                :sizes "32x32"
                :href "/favicon-32x32.png"}]
        [:link {:rel "icon"
                :type "image/png"
                :sizes "16x16"
                :href "/favicon-16x16.png"}]
        [:meta {:name "theme-color"
                :content "#ffffff"}]))

(defn index [& [request]]
  (page/html5
   [:head
    [:title "SysRev"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:script {:src "https://js.stripe.com/v3/"}]
    [:script {:src "https://unpkg.com/pdfjs-dist@2.0.489/build/pdf.js"}]
    [:script {:src "https://unpkg.com/pdfjs-dist@2.0.489/web/pdf_viewer.js"}]
    (favicon-headers)
    (apply page/include-css (css-paths :theme (user-theme request)))
    (page/include-js "/ga.js")
    (when @lucky-orange-enabled
      (page/include-js "/lo.js"))]
   [:body
    [:div {:style "display: none;"
           :id "stripe-public-key"
           :data-stripe-public-key stripe-public-key}]
    [:div {:id "app"} loading-content]
    (let [js-name (if (= (:profile env) :prod)
                    (str "sysrev-" res/build-id ".js")
                    "sysrev.js")]
      (page/include-js (str @web-asset-path "/" js-name)))]))

(defn not-found [& [request]]
  (page/html5
   [:head
    [:title "SysRev"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:script {:src "https://js.stripe.com/v3/"}]
    (favicon-headers)
    (apply page/include-css (css-paths :theme (user-theme request)))]
   [:body
    [:div {:id "app"}
     [:div.ui.container
      [:div.ui.stripe {:style "padding-top: 20px;"}
       [:h1.ui.header.huge.center.aligned
        "Not found"]]]]]))
