(ns sysrev-web.ui.user
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.ui.home :refer [similarity-card]]
            [sysrev-web.react.components :refer [link]]))

(defn user []
  (let [display-user-id (:display-id (:user @state))
        userArticles (:users @server-data)
        userArticle (first (filter (fn [u] (= display-user-id (-> u :user :id))) userArticles))
        user (:t (:user userArticle))
        articlesUnsorted (:articles userArticle)
        articles (sort-by :score articlesUnsorted)
        name (:name user)
        username (:username user)
        display-name (if (empty? name) username name)
        article-id :id
        article :article
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
                    criteria (get-in @server-data [:labels aid])]
                ^{:key (article-id adata)}
                [similarity-card (article adata) criteria score percent aid])))))]))
