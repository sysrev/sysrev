(ns sysrev-web.ui.article-page
  (:require
   [sysrev-web.base :refer
    [state server-data current-page current-user-id on-page?]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.ui.article :refer [article-info-component]]
   [sysrev-web.ui.components :refer [label-editor-component]]))

(defn update-article-active-criteria []
  (when-let [user-id (current-user-id)]
    (let [article-id (-> @state :page :article :id)]
      (ajax/pull-article-labels
       article-id
       (fn [response]
         (let [user-labels (get response user-id)]
           (swap! state
                  #(if (contains? (:page %) :article)
                     (assoc-in % [:page :article :label-values]
                               user-labels)
                     %))))))))

(add-watch
 state :article-active-criteria
 (fn [k v old new]
   (when (contains? (:page new) :article)
     (let [new-aid (-> new :page :article :id)
           old-aid (-> old :page :article :id)
           new-uid (-> new :identity :id)
           old-uid (-> old :identity :id)]
       (when (or (not= new-aid old-aid)
                 (not= new-uid old-uid))
         (update-article-active-criteria))))))

(defn article-page []
  (let [article-id (-> @state :page :article :id)
        criteria (:criteria @server-data)
        criteria-ids (keys criteria)
        overall-cid (:overall-cid @server-data)]
    [:div.ui.grid
     (let [user-id (current-user-id)
           confirmed
           (and user-id (get-in @server-data [:users user-id :labels
                                              :confirmed article-id]))
           unconfirmed
           (and user-id (get-in @server-data [:users user-id :labels
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
           (fn [cid new-value]
             (swap! state assoc-in
                    [:page :article :label-values cid] new-value)
             (->> (get-in @state [:page :article :label-values])
                  (ajax/send-tags article-id)))
           (get-in @state [:page :article :label-values])]
          [:div.ui.grid
           [:div.ui.sixteen.wide.column.center.aligned
            [:div.ui.secondary.right.labeled.icon.button
             {:class
              (if (nil? (get-in @state [:page :article :label-values overall-cid]))
                "disabled"
                "")}
             ;; {:on-click label-skip}
             "Finalize..."
             [:i.check.circle.outline.icon]]]]]))]))
