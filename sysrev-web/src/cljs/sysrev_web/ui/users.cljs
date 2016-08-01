(ns sysrev-web.ui.users
  (:require [sysrev-web.base :refer [server-data]]
            [sysrev-web.routes :as routes]
            [sysrev-web.react.components :refer [link]]
            [reagent.core :as r]))


(defn users []
  [:div.sixteen.wide.column
   [:h1 "User Activity Summary"]
   [:div.ui.cards
    (->> (:users @server-data)
         (map-indexed
           (fn [idx {:keys [user articles]}]
             (let [u (:t user)
                   uid (:id user)
                   username (:username u)
                   name (:name u)
                   display-name (if (empty? name) username name)
                   num-classified (count articles)]
               [:div.ui.fluid.card {:key uid}
                [:div.content
                 [:div.header
                  [link (fn [] (routes/user {:id uid})) display-name]]]
                [:div.content (str num-classified " articles classified")]]))))]])
