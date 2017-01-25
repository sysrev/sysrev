(ns sysrev.web.index
  (:require [hiccup.page :as page]))

(defn index [& [request]]
  (page/html5
   [:head
    [:title "SysRev"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (page/include-css "/semantic/semantic.min.css"
                      "/css/style.css")
    (page/include-js "/ga.js")]
   [:body
    [:div {:id "app"}
     [:div.ui.container
      [:div.ui.stripe {:style "padding-top: 20px;"}
       [:h1.ui.header.huge.center.aligned
        "Loading app..."]]]]
    (page/include-js "/out/sysrev.js")]))

#_
(defn not-found [& [request]]
  "Not found")

(defn not-found [& [request]]
  (page/html5
   [:head
    [:title "SysRev"]
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    (page/include-css "/semantic/semantic.min.css"
                      "/css/style.css")]
   [:body
    [:div {:id "app"}
     [:div.ui.container
      [:div.ui.stripe {:style "padding-top: 20px;"}
       [:h1.ui.header.huge.center.aligned
        "Not found"]]]]]))
