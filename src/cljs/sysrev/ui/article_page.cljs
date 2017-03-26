(ns sysrev.ui.article-page
  (:require
   [sysrev.base :refer
    [st set-loading-state get-loading-state schedule-scroll-top]]
   [sysrev.state.core :as st
    :refer [current-page current-user-id on-page?]]
   [sysrev.state.project :as project :refer [project]]
   [sysrev.state.labels :as l]
   [sysrev.ajax :as ajax]
   [sysrev.ui.article :refer
    [article-info-component label-editor-component]]
   [sysrev.ui.components :refer
    [confirm-modal-box with-tooltip inconsistent-answers-notice]])
  (:require-macros [sysrev.macros :refer [with-state]]))

(defn article-page []
  [:div
   (let [article-id (st :page :article :id)
         label-values (l/active-label-values article-id)
         overall-label-id (project :overall-label-id)
         user-id (current-user-id)
         status (l/user-article-status article-id)]
     (case status
       :logged-out
       [:div
        [article-info-component article-id false]]
       :none
       [:div
        [:div.ui.segment.article-status
         [:h3.ui.grey.header.middle.aligned
          [:i.info.circle.icon {:aria-hidden true}]
          "This article has not been selected for you to label."]]
        [article-info-component article-id false]]
       :confirmed
       [:div
        [:div.ui.segment.article-status
         [:h3.ui.green.header.middle.aligned
          [:i.info.circle.icon {:aria-hidden true}]
          "You have confirmed your labels for this article."]]
        [article-info-component article-id true user-id]]
       :unconfirmed
       [:div
        [:div.ui.segment.article-status
         [:h3.ui.yellow.header.middle.aligned
          [:i.small.info.circle.icon {:aria-hidden true}]
          "Your labels for this article are not yet confirmed."]]
        [article-info-component article-id false]
        [label-editor-component]
        #_ [confirm-modal-box #(schedule-scroll-top)]
        (let [missing (l/required-answers-missing label-values)
              disabled? ((comp not empty?) missing)
              confirm-button
              [:div.ui.primary.right.labeled.icon.button
               {:class (str (if disabled? "disabled" "")
                            " "
                            (if (get-loading-state :confirm) "loading" ""))
                :on-click
                (fn []
                  (set-loading-state :confirm true)
                  (ajax/confirm-active-labels #(schedule-scroll-top)))}
               "Confirm labels"
               [:i.check.circle.outline.icon]]]
          [:div.ui.grid.centered
           [:div.row
            (if disabled?
              [with-tooltip [:div confirm-button]]
              confirm-button)
            [:div.ui.inverted.popup.top.left.transition.hidden
             "Answer missing for a required label"]]])]))])
