(ns sysrev.ui.classify
  (:require
   [sysrev.base :refer [st ga-event]]
   [sysrev.state.core :as s :refer
    [data current-user-id current-page]]
   [sysrev.state.project :as project :refer [project]]
   [sysrev.state.labels :as labels]
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

(defn classify-page []
  (when-let [article-id (data :classify-article-id)]
    (let [user-id (current-user-id)
          email (st :identity :email)
          overall-label-id (project :overall-label-id)
          labels-path [:page :classify :label-values]
          label-values (labels/active-label-values article-id labels-path)]
      [:div.ui
       [article-info-component
        article-id false user-id (data :classify-review-status) true]
       [label-editor-component
        article-id labels-path label-values]
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
              (let [missing (labels/required-answers-missing label-values)
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
                     (ajax/pull-member-labels user-id))}
               "Next article"
               [:i.right.circle.arrow.icon]]]]]
           (let [n-unconfirmed
                 (count
                  (project :member-labels user-id :unconfirmed))
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
                   (project :member-labels user-id :unconfirmed))
                  n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
              [:div.ui.small.green.icon.button.middle.aligned
               {:on-click #(nav-scroll-top (str "/user/" user-id))
                :class (if (= 0 n-unconfirmed) "disabled" "")
                :style {:float "right"}}
               n-str [:i.small.file.text.right.icon]])]]])])))
