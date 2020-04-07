(ns sysrev.web.blog
  (:require [clj-http.client :as http]
            [hiccup.page :as page]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :refer [not-found]]
            [ring.util.response :as r]
            [honeysql.helpers :as sqlh :refer [select from order-by insert-into values]]
            [sysrev.db.core :as db :refer [do-query do-execute]]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.config :refer [env]]
            [sysrev.web.build :as build]
            [sysrev.web.index :as index]
            [sysrev.web.app :refer [not-found-response]]))

;; for clj-kondo
(declare blog-html-routes blog-routes)

(defn add-blog-entry [{:keys [url title description]}]
  (-> (insert-into :blog-entry)
      (values [{:url url :title title :description description}])
      do-execute))

(defn all-blog-entries []
  (-> (select :*)
      (from :blog-entry)
      (order-by [:date-published :desc])
      do-query vec))

(defn blog-index [& [_request]]
  (page/html5
   [:head
    [:title "Sysrev Blog"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (index/favicon-headers)
    (apply page/include-css (index/css-paths :theme "default"))
    (page/include-js "/ga-blog.js")
    (when @index/lucky-orange-enabled
      (page/include-js "/lo-blog.js"))]
   [:body
    [:div {:id "blog-app"} (loading-content :logo-url "https://sysrev.com/")]
    (let [js-name (if (= (:profile env) :prod)
                    (str "sysrev-" build/build-id ".js")
                    "sysrev.js")]
      (page/include-js (str @index/web-asset-path "/" js-name)))]))

(defroutes blog-html-routes
  (GET "/sysrev-blog/custom.css" _request
       (let [content (-> (str "https://s3.amazonaws.com/sysrev-blog/" "custom.css")
                         (http/get)
                         :body)]
         (some-> content
                 (r/response)
                 (r/header "Content-Type" "text/css; charset=utf-8"))))
  (GET "/sysrev-blog/:filename" request
       (let [filename (-> request :params :filename)
             content (try (-> (str "https://s3.amazonaws.com/sysrev-blog/" filename)
                              (http/get)
                              :body)
                          (catch Throwable _ nil))]
         (if (nil? content)
           (r/not-found (format "File not found for entry (\"%s\")." filename))
           (-> content
               (r/response)
               (r/header "Content-Type" "text/html; charset=utf-8")))))
  (GET "*" request (blog-index request))
  (not-found (not-found-response nil)))

(defroutes blog-routes
  (GET "/api/blog-entries" _request
       (let [convert-url #(let [[_ filename] (re-matches #".*/sysrev-blog/(.*)$" %)]
                            (str "/sysrev-blog/" filename))]
         {:result {:entries (->> (all-blog-entries)
                                 (mapv #(update % :url convert-url)))}})))
