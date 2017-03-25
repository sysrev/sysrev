(ns sysrev.ui.classify
  (:require
   [sysrev.base :refer [st ga-event]]
   [sysrev.state.core :as st :refer
    [data current-user-id current-page]]
   [sysrev.state.project :as project :refer [project]]
   [sysrev.state.labels :as l]
   [sysrev.util :refer [scroll-top nav-scroll-top nbsp full-size?]]
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
  [:div.ui.large.label
   [:span.ui.green.circular.label (count (l/today-labels))]
   [:span nbsp "finished today"]])

(defn classify-page []
  (when-let [article-id (data :classify-article-id)]
    (let [user-id (current-user-id)
          email (st :identity :email)
          overall-label-id (project :overall-label-id)
          label-values (l/active-label-values article-id)]
      [:div.ui
       [article-info-component
        article-id false user-id (data :classify-review-status) true]
       [label-editor-component]
       #_ [confirm-modal-box #(scroll-top)]
       (if (full-size?)
         [:div.ui.center.aligned.grid
          [:div.left.aligned.four.wide.column
           [activity-report]]
          [:div.center.aligned.eight.wide.column
           [:div.ui.grid.centered
            [:div.ui.row
             (let [missing (l/required-answers-missing label-values)
                   disabled? ((comp not empty?) missing)
                   confirm-button
                   [:div.ui.primary.right.labeled.icon.button
                    {:class (if disabled? "disabled" "")
                     :on-click
                     #_ #(do (.modal (js/$ ".ui.modal") "show"))
                     (fn [] (ajax/confirm-active-labels #(scroll-top)))}
                    "Confirm labels"
                    [:i.check.circle.outline.icon]]]
               (if disabled?
                 [with-tooltip [:div confirm-button]]
                 confirm-button))
             [:div.ui.inverted.popup.top.left.transition.hidden
              "Answer missing for a required label"]
             [:div.ui.right.labeled.icon.button
              {:on-click
               #(do (ga-event "labels" "next_article")
                    (ajax/fetch-classify-task true)
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
          {:style {:margin "0px"}}
          [:div.ui.row
           [:div.ui.three.wide.column]
           [:div.ui.ten.wide.center.aligned.column
            [:div.ui.primary.small.button
             {:class
              (if (nil?
                   (get label-values overall-label-id))
                "disabled"
                "")
              :on-click #(do (.modal (js/$ ".ui.modal") "show"))}
             "Confirm"
             [:i.small.check.circle.outline.right.icon]]
            [:div.ui.small.button
             {:on-click #(do (ajax/fetch-classify-task true)
                             (ajax/pull-member-labels user-id)
                             (scroll-top))}
             "Next"
             [:i.small.right.circle.arrow.icon]]]
           [:div.ui.three.wide.column
            {:style {:padding-right "0px"}}
            (let [n-unconfirmed
                  (count
                   (project :member-labels user-id :unconfirmed))
                  n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
              [:div.ui.small.green.icon.button.middle.aligned
               {:on-click #(nav-scroll-top (str "/user/" user-id))
                :class (if (= 0 n-unconfirmed) "disabled" "")
                :style {:float "right"}}
               n-str [:i.small.file.text.right.icon]])]]])])))
