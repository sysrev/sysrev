(ns sysrev.web.blog
  (:require [clojure.string :as str]
            [hiccup.page :as page]
            [compojure.core :refer :all]
            [compojure.route :refer [not-found]]
            [ring.util.response :as r]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction]]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.config.core :refer [env]]
            [sysrev.resources :as res]
            [sysrev.web.index :as index]
            [sysrev.web.app :refer [not-found-response]]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]))

(defn add-blog-entry [{:keys [url title description]}]
  (-> (insert-into :blog-entry)
      (values [{:url url :title title :description description}])
      do-execute))

(defn all-blog-entries []
  (-> (select :*)
      (from :blog-entry)
      (order-by [:date-published :desc])
      do-query vec))

(defn blog-index [& [request]]
  (page/html5
   [:head
    [:title "Sysrev Blog"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    #_ [:meta {:name "google-signin-scope" :content "profile email"}]
    #_ [:meta {:name "google-signin-client_id" :content index/google-oauth-id-browser}]
    #_ [:script { ;; :async true ;; :defer true
                 :src "https://apis.google.com/js/platform.js"}]
    #_ [:script {:src "https://unpkg.com/pdfjs-dist@2.0.489/build/pdf.js"}]
    #_ [:script {:src "https://unpkg.com/pdfjs-dist@2.0.489/web/pdf_viewer.js"}]
    [:script {:src "https://unpkg.com/dompurify@1.0.7/dist/purify.min.js"}]
    [:script {:src "https://unpkg.com/croppie@2.6.3/croppie.js"}]
    (index/favicon-headers)
    (apply page/include-css (index/css-paths :theme "default"))
    (page/include-js "/ga-blog.js")
    (when @index/lucky-orange-enabled
      (page/include-js "/lo-blog.js"))]
   [:body
    [:div {:id "blog-app"} (loading-content :logo-url "https://sysrev.com/")]
    (let [js-name (if (= (:profile env) :prod)
                    (str "sysrev-" res/build-id ".js")
                    "sysrev.js")]
      (page/include-js (str @index/web-asset-path "/" js-name)))]))

(defroutes blog-html-routes
  (GET "/sysrev-blog/custom.css" request
       (let [content (-> (str "https://s3.amazonaws.com/sysrev-blog/" "custom.css")
                         (http/get)
                         :body)]
         (some-> content
                 (r/response)
                 (r/header "Content-Type" "text/css; charset=utf-8"))))
  (GET "/sysrev-blog/:filename" request
       (let [filename (-> request :params :filename)
             content (try
                       (-> (str "https://s3.amazonaws.com/sysrev-blog/" filename)
                           (http/get)
                           :body)
                       (catch Throwable e
                         nil))]
         (if (nil? content)
           (r/not-found (format "File not found for entry (\"%s\")." filename))
           (-> content
               (r/response)
               (r/header "Content-Type" "text/html; charset=utf-8")))))
  (GET "*" request
       (blog-index request))
  (not-found (not-found-response nil)))

(defroutes blog-routes
  (GET "/api/blog-entries" request
       (let [convert-url
             #(let [[_ filename] (re-matches #".*/sysrev-blog/(.*)$" %)]
                (str "/sysrev-blog/" filename))]
         {:result
          {:entries
           (->> (all-blog-entries)
                (mapv #(update % :url convert-url)))}})))
