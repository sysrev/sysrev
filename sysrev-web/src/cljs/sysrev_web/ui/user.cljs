(ns sysrev-web.ui.user
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ui.home :refer [similarity-card]]
            [sysrev-web.react.components :refer [link]]))

(defn user []
  (let [display-user-id (:user (:display-id @state))
        userArticles (:users @server-data)
        userArticle (first (filter (fn [u] (= display-user-id (get-in [:user :id] u))) userArticles))
        user (:t (:user userArticle))
        articlesUnsorted (:articles userArticle)
        articles (sort-by :score articlesUnsorted)
        name (:name user)
        username (:username user)
        display-name (if (empty? name) username name)
        article-id #(-> % :item :id)
        article #(-> % :item :t)
        article-score #(- 1.0 (Math/abs (:score %)))]
    [:div.sixteen.wide.column
     [:h1 display-name]
     (doall
       (->> articles
         (map
           (fn [adata]
             (let [score (article-score adata)
                   percent (Math/round (* 100 score))
                   aid (article-id adata)
                   criteria (get-in @server-data [:articles-criteria (keyword (str aid))])]
               ^{:key (article-id adata)}
               [similarity-card {:item (article adata)} criteria score percent aid])))))]))
