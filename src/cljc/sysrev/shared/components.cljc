(ns sysrev.shared.components)

(defn loading-content
  [& {:keys [logo-url] :or {logo-url "/'"}}]
  [:div#main-content
   [:div.ui.menu.site-menu
    [:div.ui.container
     [:a.header.item {:href logo-url}
      [:img.ui.middle.aligned.image
       {:src "/SysRev_header_2.png" :alt "SysRev"
        :width "90" :height "28"}]]
     [:div.right.menu]]]])

(defn table [columns rows & {:keys [header]}]
  [:table.ui.compact.striped.table
   (when header [:thead [:tr [:th {:colSpan (count columns)} [:h4.ui.header header]]]])
   [:thead [:tr (for [col columns] ^{:key col}[:th (name col)])]]
   [:tbody (for [row rows]
             ^{:key (cljs.core/random-uuid)}[:tr (for [col columns]
                                                   ^{:key (cljs.core/random-uuid)}[:td (col row)])])]])

(def colors {:grey           "rgba(160, 160, 160, 0.5)"
             :turquoise      "rgba(64,  224, 208, 0.8)"
             :green          "rgba(33,  186, 69,  0.55)"
             :bright-green   "rgba(33,  186, 69,  0.9)"
             :gold           "rgba(255, 215, 0,   1.0)"
             :dim-green      "rgba(33,  186, 69,  0.35)"
             :orange         "rgba(242, 113, 28,  0.55)"
             :bright-orange  "rgba(242, 113, 28,  1.0)"
             :dim-orange     "rgba(242, 113, 28,  0.35)"
             :pink           "rgb(231,84,128,1.0)"
             :red            "rgba(255, 86,  77,  1.0)"
             :transparent-red "rgba(255, 86,  77,  0.75)"
             :blue           "rgba(84,  152, 169, 1.0)"
             :purple         "rgba(146, 29,  252, 0.5)"
             :bright-purple  "rgba(146, 29,  252, 1.0)"
             :select-blue    "rgba(50,  150, 226, 1.0)"})
