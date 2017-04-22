(ns sysrev.ui.classify
  (:require
   [sysrev.base :refer
    [st ga-event get-loading-state set-loading-state schedule-scroll-top]]
   [sysrev.state.core :as st :refer
    [data current-user-id current-page]]
   [sysrev.state.project :as project :refer [project]]
   [sysrev.state.labels :as l]
   [sysrev.util :refer [nav-scroll-top nbsp full-size?]]
   [sysrev.routes :refer [data-initialized?]]
   [sysrev.ui.components :refer
    [three-state-selection with-tooltip confirm-modal-box
     inconsistent-answers-notice]]
   [sysrev.ui.article :refer
    [article-info-component label-editor-component]]
   [sysrev.ajax :as ajax]
   [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [with-state]]))


(defn activity-report []
  (if (full-size?)
    [:div.ui.large.label.activity-report
     [:span.ui.green.circular.label (count (l/today-labels))]
     [:span nbsp "finished today"]]
    [:div.ui.large.label.activity-report
     [:span.ui.tiny.green.circular.label (count (l/today-labels))]
     [:span nbsp "today"]]))

(defn classify-page []
  (when-let [article-id (data :classify-article-id)]
    (let [user-id (current-user-id)
          email (st :identity :email)
          overall-label-id (project :overall-label-id)
          label-values (l/active-label-values article-id)
          missing (l/required-answers-missing label-values)]
      [:div.ui
       [article-info-component
        article-id false user-id (data :classify-review-status) true]
       [label-editor-component]
       #_ [confirm-modal-box #(schedule-scroll-top)]
       (if (full-size?)
         [:div.ui.center.aligned.grid
          [:div.left.aligned.four.wide.column
           [activity-report]]
          [:div.center.aligned.eight.wide.column
           [:div.ui.grid.centered
            [:div.ui.row
             (let [disabled? ((comp not empty?) missing)
                   confirm-button
                   [:div.ui.primary.right.labeled.icon.button
                    {:class (str (if disabled? "disabled" "")
                                 " "
                                 (if (get-loading-state :confirm) "loading" ""))
                     :on-click
                     (fn []
                       (set-loading-state :confirm true)
                       (ajax/confirm-active-labels nil #(schedule-scroll-top)))}
                    "Confirm labels"
                    [:i.check.circle.outline.icon]]]
               (if disabled?
                 [with-tooltip [:div confirm-button]]
                 confirm-button))
             [:div.ui.inverted.popup.top.left.transition.hidden
              "Answer missing for a required label"]
             [:div.ui.right.labeled.icon.button
              {:class (if (get-loading-state :next-article)
                        "loading" "")
               :on-click
               (fn []
                 (set-loading-state :next-article true)
                 (ga-event "labels" "next_article")
                 (ajax/fetch-classify-task true #(schedule-scroll-top))
                 (ajax/pull-member-labels user-id))}
              "Next article"
              [:i.right.circle.arrow.icon]]]]]
          (let [n-unconfirmed
                (count
                 (project :member-labels user-id :unconfirmed))
                n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
            [:div.ui.right.aligned.four.wide.column
             [:div.ui.buttons.right.floated
              [:div.ui.labeled.button
               {:on-click #(nav-scroll-top (str "/user/" user-id))
                :class (if (= 0 n-unconfirmed) "disabled" "")}
               [:div.ui.green.button
                (str "Review unconfirmed")]
               [:a.ui.label n-str nbsp [:i.file.text.icon]]]]])]
         [:div.ui.grid
          {:style {:margin "-0.5em"}}
          [:div.ui.row
           [:div.ui.four.wide.column.left-column
            [activity-report]]
           [:div.ui.eight.wide.center.aligned.column
            (let [disabled? ((comp not empty?) missing)]
              [:div.ui.primary.small.button
               {:class
                (str (if disabled? "disabled" "")
                     " "
                     (if (get-loading-state :confirm) "loading" ""))
                :on-click
                (fn []
                  (set-loading-state :confirm true)
                  (ajax/confirm-active-labels nil #(schedule-scroll-top)))}
               "Confirm"
               [:i.small.check.circle.outline.right.icon]])
            [:div.ui.small.button
             {:class (if (get-loading-state :next-article)
                       "loading" "")
              :on-click
              (fn []
                (set-loading-state :next-article true)
                (ga-event "labels" "next_article")
                (ajax/fetch-classify-task true #(schedule-scroll-top))
                (ajax/pull-member-labels user-id))}
             "Next"
             [:i.small.right.circle.arrow.icon]]]
           [:div.ui.four.wide.column.right-column
            (let [n-unconfirmed
                  (count
                   (project :member-labels user-id :unconfirmed))
                  n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
              [:div.ui.small.green.icon.button.middle.aligned
               {:on-click #(nav-scroll-top (str "/user/" user-id))
                :class (if (= 0 n-unconfirmed) "disabled" "")
                :style {:float "right"}}
               n-str [:i.small.file.text.right.icon]])]]])])))
