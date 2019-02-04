(ns sysrev.web.index
  (:require [clojure.string :as str]
            [hiccup.page :as page]
            [sitemap.core :refer [generate-sitemap]]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.config.core :refer [env]]
            [sysrev.paypal :refer [paypal-env paypal-client-id]]
            [sysrev.resources :as res]
            [sysrev.stripe :refer [stripe-public-key stripe-client-id]]
            [sysrev.util :refer [today-string-site-map]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.shared.text :as text]))

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
   "/css/dropzone.min.css"
   (format "/css/style.%s.css" theme)
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

(def google-oauth-id-browser
  "663198182926-2scj6i34qibj3fjfrtkmphktk9vo23u5.apps.googleusercontent.com")

(defn index [& [request]]
  (page/html5
   [:head
    [:title "Sysrev"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (when (= (:uri request) "/")
      [:meta {:name "Description"
              :content (str "Sysrev" (first text/site-intro-text))}])
    #_ [:meta {:name "google-signin-scope" :content "profile email"}]
    #_ [:meta {:name "google-signin-client_id" :content google-oauth-id-browser}]
    #_ [:script { ;; :async true ;; :defer true
                 :src "https://apis.google.com/js/platform.js"}]
    [:script {:src "https://js.stripe.com/v3/"}]
    [:script {:src "https://www.paypalobjects.com/api/checkout.js"}]
    ;; issues with SRI: https://shkspr.mobi/blog/2018/11/major-sites-running-unauthenticated-javascript-on-their-payment-pages/
    ;; to get sri from unpkg:
    ;; see: https://github.com/unpkg/unpkg.com/issues/48
    ;; https://unpkg.com/pdfjs-dist@2.0.489/build/pdf.js?meta
    [:script {:src "https://unpkg.com/pdfjs-dist@2.0.489/build/pdf.js"
              :integrity "sha384-rIKlQB9P0e4mbgFLuJ2xBYtb6H0hFqlAcuVOiVcoe5YOC4SW5I0+iekM7RIY8Lk0"
              :crossorigin "anonymous"}]
    [:script {:src "https://unpkg.com/pdfjs-dist@2.0.489/web/pdf_viewer.js"
              :integrity "sha384-FVC5kR7Wzc7qaZ3O99Q7bSdrAYQJU4eDVt3icJnuHwfTMiM27DfvBF3nJuPQ1Ybe"
              :crossorigin "anonymous"}]
    [:script {:src "https://unpkg.com/dompurify@1.0.7/dist/purify.min.js"
              :integrity "sha384-HENZbXzFIaIvM6Lf55V7Bt1g1e2I57cJ/ChnBirhGwF0drEO78qhs24L8mfC2Wh6"
              :crossorigin "anonymous"}]
    (favicon-headers)
    (apply page/include-css (css-paths :theme (user-theme request)))
    (page/include-js "/ga.js")
    (when @lucky-orange-enabled
      (page/include-js "/lo.js"))]
   [:body
    [:div {:style "display: none;"
           :id "stripe-public-key"
           :data-stripe-public-key stripe-public-key}]
    [:div {:style "display: none;"
           :id "stripe-client-id"
           :data-stripe-client-id stripe-client-id}]
    [:div {:style "display: none;"
           :id "paypal-env"
           :data-paypal-env paypal-env}]
    [:div {:style "display: none;"
           :id "paypal-client-id"
           :data-paypal-client-id paypal-client-id}]
    [:div {:id "app"} (loading-content)]
    (let [js-name (if (= (:profile env) :prod)
                    (str "sysrev-" res/build-id ".js")
                    "sysrev.js")]
      (page/include-js (str @web-asset-path "/" js-name)))]))

(defn not-found [& [request]]
  (page/html5
   [:head
    [:title "Sysrev"]
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

(defn sysrev-sitemap
  []
  (let [project->site-map-entry (fn [project]
                                  {:loc (str "https://sysrev.com/p/" (:project-id project))
                                   :lastmod (today-string-site-map)
                                   :changefreq "daily"})
        project-site-maps (map project->site-map-entry
                               (project/all-public-projects))]
    (generate-sitemap project-site-maps)))
