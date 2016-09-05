(ns sysrev.web.index
  (:require [hiccup.page :as page]))

(defn index [& req]
  (page/html5
   [:head
    [:title "Systematic Review"]
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    (page/include-js "/vendor/jquery/dist/jquery.min.js"
                     "/semantic/semantic.min.js")
    (page/include-css "/semantic/semantic.min.css"
                      "/css/style.css")]
   [:body
    [:div {:id "app"}
     [:div.ui.container
      [:div.ui.stripe {:style "padding-top: 20px;"}
       [:h1.ui.header.huge.center.aligned
        "Loading app..."]]]]
    (page/include-js "/out/sysrev_web.js")]))
