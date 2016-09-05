(ns sysrev-web.ui.user-profile
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.data :refer [user-article-ids-sorted]]
            [sysrev-web.ui.components :refer [debug-box]]
            [sysrev-web.ui.article :refer [article-short-info-component]]))

(defn user-profile-page []
  (let [user-id (get-in @state [:page :user-profile :user-id])
        user (get-in @server-data [:sysrev :users user-id :user])
        display-name (or (:username user) (:name user) (:email user))
        article-ids (user-article-ids-sorted user-id :score)]
    [:div.sixteen.wide.column
     [:h1 display-name]
     (doall
      (for [article-id article-ids]
        ^{:key {:user-article {:a article-id :u user-id}}}
        [article-short-info-component article-id true user-id]))]))
