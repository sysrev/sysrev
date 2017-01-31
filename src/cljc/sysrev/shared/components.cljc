(ns sysrev.shared.components)

(def loading-content
  [:div.main-content
   [:div.ui.top.menu.site-menu
    [:div.ui.container
     [:a.header.item {:href "/"}
      [:h3.ui.blue.header "sysrev.us"]]
     [:a.item.loading-indicator
      [:div.ui.small.active.inline.loader]]
     [:div.right.menu]]]])
