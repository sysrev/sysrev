(ns sysrev-web.ui.article-page
  (:require
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.core :refer [current-page current-user-id on-page?]]
   [sysrev-web.state.data :as d]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.ui.article :refer
    [article-info-component label-editor-component]])
  (:require-macros [sysrev-web.macros :refer [with-state]]))

(defn article-page []
  (let [article-id (-> @state :page :article :id)
        criteria (-> @state :data :criteria)
        criteria-ids (keys criteria)
        overall-cid (-> @state :data :overall-cid)]
    [:div.ui.grid
     (let [user-id (current-user-id)
           confirmed
           (and user-id (get-in @state [:data :users user-id :labels
                                        :confirmed article-id]))
           unconfirmed
           (and user-id (get-in @state [:data :users user-id :labels
                                        :unconfirmed article-id]))
           status (cond (nil? user-id) :logged-out
                        confirmed :confirmed
                        unconfirmed :unconfirmed
                        :else :none)]
       (case status
         :logged-out
         [:div
          [article-info-component article-id false]]
         :none
         [:div
          [:div.ui.segment
           [:h3.ui.grey.header
            [:i.remove.circle.outline.icon.left.floated {:aria-hidden true}]
            "This article has not been selected for you to label."]]
          [article-info-component article-id false]]
         :confirmed
         [:div
          [:div.ui.segment
           [:h3.ui.green.header
            [:i.check.circle.outline.icon.left.floated {:aria-hidden true}]
            "You have already confirmed your labels for this article."]]
          [article-info-component article-id true user-id]]
         :unconfirmed
         [:div
          [:div.ui.segment
           [:h3.ui.green.header
            [:i.selected.radio.icon.left.floated {:aria-hidden true}]
            "Your labels for this article are not yet confirmed."]]
          [article-info-component article-id false]
          [label-editor-component
           article-id [:page :article :label-values]]
          [:div.ui.grid
           [:div.ui.sixteen.wide.column.center.aligned
            [:div.ui.primary.right.labeled.icon.button
             {:class
              (if (nil?
                   (get (d/active-label-values
                         article-id [:page :article :label-values])
                        overall-cid))
                "disabled"
                "")}
             "Finalize..."
             [:i.check.circle.outline.icon]]]]]))]))
