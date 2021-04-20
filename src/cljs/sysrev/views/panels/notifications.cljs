(ns sysrev.views.panels.notifications
  (:require [cljs-time.coerce :as tc]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-sub subscribe]]
            [sysrev.data.core :refer [def-data load-data]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.state.notifications]
            [sysrev.views.semantic :refer [Segment Message]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.views.panels.user.profile :refer [User]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]
            [sysrev.util :refer [time-elapsed-string]]))

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

(reg-event-fx :consume-notification
              (fn [{:keys [db]} [_ {:keys [id uri] :as notification}]]
                (let [notifications (->> db :notifications
                                      (mapv #(if (= notification %)
                                               (assoc notification :viewed (js/Date.))
                                               %)))]
                  {:db (assoc db :notifications notifications)
                   :dispatch-n [[:notifications/set-open false]
                                [:nav uri]]})))

(defn Notification [{:keys [created html id viewed] :as notification}]
  [:li {:class "notification-item"
        :style {:list-style "none"
                :margin-bottom "10px"}}
   [:a.item {:on-click #(dispatch [:consume-notification notification])
             :style {:font-size "17px"}}
    [:span {:style {:display "inline"}}
     [:span {:dangerouslySetInnerHTML {:__html html}}]
     [:br] [:br]
     [:span {:class "blue-text"
             :style {:font-weight "bold"}}
      (time-elapsed-string (tc/from-date created))]]]])

(defn NotificationsDropdown []
  (let [notifications (->> @(subscribe [:notifications])
                        (remove :viewed)
                        (sort-by :created)
                        reverse)]
    [:div {:class "notifications-container"
           :style {:background-color "white"
                   :border "1px solid rgba(100, 100, 100, .4)"
                   :border-radius "3px"
                   :display "block"
                   :overflow "visible"
                   :position "absolute"
                   :right 0
                   :top "3.3em"
                   :-webkit-box-shadow "0 3px 8px rgba(0, 0, 0, .25)"
                   :width "500px"
                   :z-index 1000}}
     [:div {:class "notifications-title ui header"
            :style {:border-bottom "1px solid #dddddd"
                    :font-weight "bold"
                    :padding "5px"}}
           "Notifications"]
     (into [:ul]
       (mapv #(-> [Notification %]) notifications))
     [:div {:class "notifications-footer"
            :style {:background-color "#e9eaed"
                    :border-top "1px solid #dddddd"
                    :font-size "15px"
                    :font-weight "bold"
                    :padding "8px"
                    :text-align "center"}}
      [:a {:href "#"
           :style {:color "black"}}
       "See All"]]]))

(defn NotificationsButton []
  (let [notifications @(subscribe [:notifications])
        new-count (count (remove :viewed notifications))
        open? (some-> (subscribe [:notifications/open?]) deref)]
    [:<>
     [:a {:class "item"
          :on-click #(toggle-open open?)}
      [:i {:class "black bell outline icon"
           :style {:font-size "22px"}}]
      (when-not (zero? new-count)
        [:span {:class "notifications-count"
                :style {:background "#cc0000"
                        :border-radius "9px"
                        :-moz-border-radius "9px"
                        :-webkit-border-radius "9px"
                        :color "white"
                        :font-size "11px"
                        :font-weight "bold"
                        :margin-left "12px"
                        :margin-top "-15px"
                        :padding "3px 7px 3px 7px"
                        :position "absolute"}}
         new-count])]
     (when open?
       [NotificationsDropdown])]))

