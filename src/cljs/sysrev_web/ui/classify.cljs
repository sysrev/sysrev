(ns sysrev-web.ui.classify
  (:require
   [sysrev-web.base :refer [state ga-event]]
   [sysrev-web.state.core :refer [current-user-id current-page]]
   [sysrev-web.util :refer [scroll-top nav-scroll-top nbsp full-size?]]
   [sysrev-web.routes :refer [data-initialized?]]
   [sysrev-web.ui.components :refer
    [three-state-selection with-tooltip confirm-modal-box
     inconsistent-answers-notice]]
   [sysrev-web.ui.article :refer
    [article-info-component label-editor-component]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.state.data :as d :refer [data]]
   [reagent.core :as r])
  (:require-macros [sysrev-web.macros :refer [with-state]]))

(defn classify-page []
  (when-let [article-id (data :classify-article-id)]
    (let [user-id (current-user-id)
          email (-> @state :identity :email)
          overall-label-id (d/project :overall-label-id)
          labels-path [:page :classify :label-values]
          label-values (d/active-label-values article-id labels-path)]
      [:div.ui
       {:style {:margin-bottom "40px"}}
       [article-info-component
        article-id false user-id (data :classify-review-status) true]
       [label-editor-component
        article-id labels-path label-values]
       [inconsistent-answers-notice label-values]
       [confirm-modal-box
        #(data :classify-article-id)
        labels-path
        (fn [] (scroll-top))]
       (if (full-size?)
         [:div.ui.grid
          [:div.ui.row
           [:div.ui.five.wide.column]
           [:div.ui.six.wide.column.center.aligned
            [:div.ui.grid.centered
             [:div.row
              (let [missing (d/required-answers-missing label-values)
                    disabled? ((comp not empty?) missing)
                    confirm-button
                    [:div.ui.primary.right.labeled.icon.button
                     {:class (if disabled? "disabled" "")
                      :on-click #(do (.modal (js/$ ".ui.modal") "show"))}
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
                     (ajax/pull-member-labels user-id)
                     (scroll-top))}
               "Next article"
               [:i.right.circle.arrow.icon]]]]]
           (let [n-unconfirmed
                 (count
                  (d/project [:member-labels user-id :unconfirmed]))
                 n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
             [:div.ui.five.wide.column
              [:div.ui.buttons.right.floated
               [:div.ui.labeled.button
                {:on-click #(nav-scroll-top (str "/user/" user-id))
                 :class (if (= 0 n-unconfirmed) "disabled" "")}
                [:div.ui.green.button
                 (str "Review unconfirmed")]
                [:a.ui.label n-str nbsp [:i.file.text.icon]]]]])]]
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
                   (d/project [:member-labels user-id :unconfirmed]))
                  n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
              [:div.ui.small.green.icon.button.middle.aligned
               {:on-click #(nav-scroll-top (str "/user/" user-id))
                :class (if (= 0 n-unconfirmed) "disabled" "")
                :style {:float "right"}}
               n-str [:i.small.file.text.right.icon]])]]])])))
