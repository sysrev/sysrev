(ns sysrev.shared.components)

(defn loading-content
  [& {:keys [logo-url] :or {logo-url "/'"}}]
  [:div.main-content
   [:div.ui.menu.site-menu
    [:div.ui.container
     [:a.header.item {:href logo-url}
      [:img.ui.middle.aligned.image
       {:src "/SysRev_header.png" :alt "SysRev"
        :width "65" :height "20"}]]
     [:div.right.menu]]]])
