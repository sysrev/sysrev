(ns sysrev-web.ui.user-profile
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.ui.components :refer [debug-box]]
            [sysrev-web.ui.article :refer [article-info-component]]))

(defn user-profile-page []
  (let [uid (-> @state :page :user-profile :user-id)
        user (get-in @server-data [:users uid :user])
        articles-map (get-in @server-data [:users uid :articles])
        article-ids (concat (:includes articles-map)
                            (:excludes articles-map))
        articles (->> article-ids
                      (mapv #(get-in @server-data [:articles %]))
                      (remove nil?)
                      (sort-by :score))
        email (:email user)
        name (:name user)
        username (:username user)
        display-name (or name username email)
        article-score #(- 1.0 (Math/abs (:score %)))]
    [:div.sixteen.wide.column
     [:h1 display-name]
     (doall
      (->> articles
           (map
            (fn [adata]
              (let [score (article-score adata)
                    percent (Math/round (* 100 score))
                    article (:item adata)
                    aid (:article_id article)
                    criteria (get-in @server-data [:labels aid])]
                ^{:key {:user-article aid}}
                [article-info-component aid uid])))))]))
