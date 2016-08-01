(ns sysrev-web.ui.user
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ui.home :refer [similarity-card]]
            [sysrev-web.react.components :refer [link]]))

(defn user []
  (let [display-user-id (:user (:display-id @state))
        userArticles (:users @server-data)
        userArticle (first (filter (fn [u] (= display-user-id (get-in [:user :id] u))) userArticles))
        user (:t (:user userArticle))
        articles (:articles userArticle)
        name (:name user)
        username (:username user)
        display-name (if (empty? name) username name)]
    [:div.sixteen.wide.column
     [:h1 display-name]
     (->> articles
       (map
         (fn [article]
           [similarity-card {:item (:t article)} nil nil nil (:id article)])))]))

