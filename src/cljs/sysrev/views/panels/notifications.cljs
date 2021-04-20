(ns sysrev.views.panels.notifications
  (:require [reagent.core :as r]
            [re-frame.core :refer [dispatch reg-event-db reg-sub subscribe]]
            [sysrev.data.core :refer [def-data load-data]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.state.notifications]
            [sysrev.views.semantic :refer [Segment Message]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.views.panels.user.profile :refer [User]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

(reg-sub :notifications
         (fn [db & args]
           (get db :notifications)))

(reg-sub :notifications/open?
         (fn [db & args]
           (get db :notifications/open?)))

(reg-event-db :notifications/set-open
              (fn [db [_ open?]]
                (assoc db :notifications/open? open?)))

(defn toggle-open [open?]
  (dispatch [:notifications/set-open (not open?)]))

(defn Notification [{:keys [action id html viewed]}]
  [:li {:style (when-not viewed {:font-weight "bold"})
        :dangerouslySetInnerHTML {:__html html}}])

(defn NotificationsDropdown []
  (let [notifications @(subscribe [:notifications])]
    (into [:ul {:style {:background-color "white"
                        :display "block"
                        :position "absolute"
                        :z-index 1000}}]
      (mapv #(-> [Notification %]) notifications))))

(defn NotificationsButton []
  (let [notifications @(subscribe [:notifications])
        new-count (count (remove :viewed notifications))
        open? (some-> (subscribe [:notifications/open?]) deref)]
    [:div {:on-click #(toggle-open open?)}
     [:i {:class "black bell outline icon"
          :style {:font-size "22px"}}]
     (when-not (zero? new-count)
       [:span {:style {:color "red"}}
        " (" new-count ")"])
     (when open?
       [NotificationsDropdown])]))

