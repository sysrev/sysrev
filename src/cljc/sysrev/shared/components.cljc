(ns sysrev.shared.components)

(def loading-content
  [:div.main-content
   [:div.ui.menu.site-menu
    [:div.ui.container
     [:a.header.item {:href "/"}
      [:img.ui.middle.aligned.image
       {:src "/SysRev_header.png" :alt "SysRev"
        :width "65" :height "20"}]]
     [:a.item.loading-indicator
      [:div.ui.small.active.inline.loader]]
     [:div.right.menu]]]])
