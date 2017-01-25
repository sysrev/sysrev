(ns sysrev.ui.article-page
  (:require
   [sysrev.base :refer [state]]
   [sysrev.util :refer [scroll-top]]
   [sysrev.state.core :refer [current-page current-user-id on-page?]]
   [sysrev.state.data :as d]
   [sysrev.ajax :as ajax]
   [sysrev.ui.article :refer
    [article-info-component label-editor-component]]
   [sysrev.ui.components :refer
    [confirm-modal-box with-tooltip inconsistent-answers-notice]])
  (:require-macros [sysrev.macros :refer [with-state]]))

(defn article-page []
  [:div
   (let [article-id (-> @state :page :article :id)
         labels-path [:page :article :label-values]
         label-values (d/active-label-values article-id labels-path)
         overall-label-id (d/project :overall-label-id)
         user-id (current-user-id)
         status (d/user-article-status article-id)]
     (case status
       :logged-out
       [:div
        [article-info-component article-id false]]
       :none
       [:div
        [:div.ui.segment
         [:h3.ui.grey.header.middle.aligned {:style {:margin "-5px"}}
          [:i.info.circle.icon {:aria-hidden true}]
          "This article has not been selected for you to label."]]
        [article-info-component article-id false]]
       :confirmed
       [:div
        [:div.ui.segment
         [:h3.ui.green.header.middle.aligned {:style {:margin "-5px"}}
          [:i.info.circle.icon {:aria-hidden true}]
          "You have confirmed your labels for this article."]]
        [article-info-component article-id true user-id]]
       :unconfirmed
       [:div
        {:style {:margin-bottom "40px"}}
        [:div.ui.segment
         [:h3.ui.yellow.header.middle.aligned {:style {:margin "-5px"}}
          [:i.info.circle.icon {:aria-hidden true}]
          "Your labels for this article are not yet confirmed."]]
        [article-info-component article-id false]
        [label-editor-component
         article-id labels-path label-values]
        [inconsistent-answers-notice label-values]
        [confirm-modal-box
         #(-> @state :page :article :id)
         labels-path
         (fn [] (scroll-top))]
        (let [missing (d/required-answers-missing label-values)
              disabled? ((comp not empty?) missing)
              confirm-button
              [:div.ui.primary.right.labeled.icon.button
               {:class (if disabled? "disabled" "")
                :on-click #(do (.modal (js/$ ".ui.modal") "show"))}
               "Confirm labels"
               [:i.check.circle.outline.icon]]]
          [:div.ui.grid.centered
           [:div.row
            (if disabled?
              [with-tooltip [:div confirm-button]]
              confirm-button)
            [:div.ui.inverted.popup.top.left.transition.hidden
             "Answer missing for a required label"]]])]))])
