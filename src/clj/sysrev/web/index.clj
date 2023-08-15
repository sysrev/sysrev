(ns sysrev.web.index
  (:require [clojure.string :as str]
            [hiccup.page :as page]
            [sitemap.core :refer [generate-sitemap]]
            [sysrev.config :refer [env]]
            [sysrev.db.queries :as q]
            [sysrev.web.build :as build]
            [sysrev.payment.paypal :refer [paypal-env paypal-client-id]]
            [sysrev.payment.stripe :refer [stripe-public-key stripe-client-id]]
            [sysrev.shared.text :as text]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.project.core :as project]
            [sysrev.util :as util :refer [today-string]]))

(defn- user-theme [{:keys [session] :as _request}]
  (if-let [user-id (-> session :identity :user-id)]
    (or (some-> (q/get-user user-id :settings) :ui-theme str/lower-case)
        "default")
    (or (some-> session :settings :ui-theme str/lower-case)
        "default")))

(defn css-paths [& {:keys [theme] :or {theme "default"}}]
  [(format "/css/semantic/%s/semantic.min.css" theme)
   "/css/dropzone.min.css"
   (format "/css/style.%s.css" theme)
   "/css/js-components.css"
   "https://fonts.googleapis.com/css?family=Open+Sans:400,600,700"])

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

;;; issues with SRI: https://shkspr.mobi/blog/2018/11/major-sites-running-unauthenticated-javascript-on-their-payment-pages/
;;; to get sri from unpkg:
;;; see: https://github.com/unpkg/unpkg.com/issues/48
;;; https://unpkg.com/pdfjs-dist@2.0.489/build/pdf.js?meta

(defn index [request & [maintainence-msg]]
  (page/html5
   [:head
    [:title (text/uri-title (:uri request))]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:meta {:name "Description" :content (text/uri-meta-description (:uri request))}]
    (when-not (util/should-robot-index? (:uri request))
      [:meta {:name "robots" :content "noindex,nofollow"}])
    (apply page/include-css (css-paths :theme (user-theme request)))
    (favicon-headers)
    [:script {:async true
              :src (str "https://www.paypal.com/sdk/js?client-id=" (paypal-client-id)
                        "&currency=USD&disable-funding="
                        (str/join "," ["credit" "card"]))
              :type "text/javascript"}]
    [:script {:defer true
              :data-domain "sysrev.com"
              :src "https://plausible.io/js/script.js"}]]
   [:body
    [:div {:style "display: none;"
           :id "graphql-endpoint"
           :data-graphql-endpoint
           (get-in request [:sr-context :config :graphql-endpoint])}]
    [:div {:style "display: none;"
           :id "datapub-ws"
           :data-datapub-ws
           (get-in request [:sr-context :config :datapub-ws])}]
    [:div {:style "display: none;"
           :id "stripe-public-key"
           :data-stripe-public-key (stripe-public-key)}]
    [:div {:style "display: none;"
           :id "stripe-client-id"
           :data-stripe-client-id (stripe-client-id)}]
    [:div {:style "display: none;"
           :id "paypal-env"
           :data-paypal-env (paypal-env)}]
    [:div {:style "display: none;"
           :id "paypal-client-id"
           :data-paypal-client-id (paypal-client-id)}]
    [:div {:id "app"} (loading-content)]
    (if maintainence-msg
      [:div.ui.container
       [:div.ui.negative.icon.message
        [:i.warning.icon]
        [:div.content maintainence-msg]]]
      (let [js-name (if (= (:profile env) :prod)
                      (str "sysrev-" build/build-id ".js")
                      "sysrev.js")]
        (page/include-js (str (get-in request [:sr-context :config :web-server :web-asset-path])
                              "/" js-name))))
    (when-not (:local-only env)
      [:div
       [:script {:type "text/javascript"} "_linkedin_partner_id = \"2703428\"; window._linkedin_data_partner_ids = window._linkedin_data_partner_ids || []; window._linkedin_data_partner_ids.push(_linkedin_partner_id);"]
       [:script {:type "text/javascript"} "(function(){var s = document.getElementsByTagName(\"script\")[0]; var b = document.createElement(\"script\"); b.type = \"text/javascript\";b.async = true; b.src = \"https://snap.licdn.com/li.lms-analytics/insight.min.js\"; s.parentNode.insertBefore(b, s);})();"]
       [:noscript [:img {:id "linkedin-img" :height "1" :width "1" :style "display:none;" :alt "" :src "https://px.ads.linkedin.com/collect/?pid=2703428&fmt=gif"}]]])]))

(defn not-found [& [request]]
  (page/html5
   [:head
    [:title (text/uri-title (:uri request))]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (favicon-headers)
    (apply page/include-css (css-paths :theme (user-theme request)))]
   [:body
    [:div {:id "app"}
     [:div.ui.container
      [:div.ui.stripe {:style "padding-top: 20px;"}
       [:h1.ui.header.huge.center.aligned
        "Not found"]]]]]))

(defn sysrev-sitemap  []
  (->> (project/all-public-projects)
       (map (fn [project]
              {:loc (str "https://sysrev.com/p/" (:project-id project))
               :lastmod (today-string :date)
               :changefreq "daily"}))
       (generate-sitemap)))
