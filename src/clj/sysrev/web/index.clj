(ns sysrev.web.index
  (:require [clojure.string :as str]
            [hiccup.page :as page]
            [sitemap.core :refer [generate-sitemap]]
            [sysrev.config :refer [env]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.web.build :as build]
            [sysrev.payment.paypal :refer [paypal-env paypal-client-id]]
            [sysrev.payment.stripe :refer [stripe-public-key stripe-client-id]]
            [sysrev.shared.text :as text]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.project.core :as project]
            [sysrev.util :refer [today-string]]))

(defonce web-asset-path (atom "/out"))

(defonce lucky-orange-enabled (atom (= (:profile env) :prod)))

(defn set-web-asset-path [& [path]]
  (let [path (or path "/out")]
    (reset! web-asset-path path)))

(defn- user-theme [{:keys [session] :as _request}]
  (if-let [user-id (-> session :identity :user-id)]
    (or (some-> (q/get-user user-id :settings) :ui-theme str/lower-case)
        "default")
    (or (some-> session :settings :ui-theme str/lower-case)
        "default")))

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

(def ^:unused google-oauth-id-browser
  "663198182926-2scj6i34qibj3fjfrtkmphktk9vo23u5.apps.googleusercontent.com")

;;; issues with SRI: https://shkspr.mobi/blog/2018/11/major-sites-running-unauthenticated-javascript-on-their-payment-pages/
;;; to get sri from unpkg:
;;; see: https://github.com/unpkg/unpkg.com/issues/48
;;; https://unpkg.com/pdfjs-dist@2.0.489/build/pdf.js?meta

(defn title [uri]
  (let [project-url? (clojure.string/includes? uri "/p/")]
    (cond
      (= uri "/") (str "Built for data miners | Sysrev")
      (= uri "/lit-review") (str "Free Literature Review | Sysrev")
      (= uri "/data-extraction") (str "Advanced Data Extraction | Sysrev")
      (= uri "/systematic-review") (str "Modern Systematic Review | Sysrev")
      (= uri "/managed-review") (str "Expert Data Extraction | Sysrev")
      (= uri "/register") "Start Your Free Trial | Sysrev"
      project-url? (-> (re-find #"/p/([0-9]+)",uri) last Integer/parseInt
                       project/project-settings :name) ;TODO add an authentication check for private projects
      :else (str "Sysrev"))))

(defn index [& [request maintainence-msg]]
  (page/html5
   [:head
    [:title (sysrev.shared.text/uri-title (:uri request))]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:meta {:name "Description" :content (sysrev.shared.text/uri-meta-description (:uri request))}]
    #_ [:meta {:name "google-signin-scope" :content "profile email"}]
    #_ [:meta {:name "google-signin-client_id" :content google-oauth-id-browser}]
    #_ [:script {:src "https://apis.google.com/js/platform.js"
                 ;; :async true :defer true
                 }]
    [:script {:src "https://js.stripe.com/v3/"}]
    [:script {:src (str "https://www.paypal.com/sdk/js?client-id=" (paypal-client-id)
                        "&currency=USD&disable-funding="
                        (str/join "," ["credit" "card"]))}]
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
        (page/include-js (str @web-asset-path "/" js-name))))]))

(defn not-found [& [request]]
  (page/html5
   [:head
    [:title (sysrev.shared.text/uri-title (:uri request))]
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

(defn sysrev-sitemap  []
  (->> (project/all-public-projects)
       (map (fn [project]
              {:loc (str "https://sysrev.com/p/" (:project-id project))
               :lastmod (today-string :date)
               :changefreq "daily"}))
       (generate-sitemap)))
