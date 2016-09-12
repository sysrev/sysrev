(ns sysrev-web.ui.classify
  (:require
   [sysrev-web.base :refer [state server-data current-user-id current-page]]
   [sysrev-web.util :refer [nav-scroll-top nbsp]]
   [sysrev-web.ajax :refer [send-tags pull-article-labels]]
   [sysrev-web.routes :refer [data-initialized?]]
   [sysrev-web.ui.components :refer
    [three-state-selection with-tooltip label-editor-component]]
   [sysrev-web.ui.article :refer [article-info-component]]
   [sysrev-web.classify :refer
    [label-queue label-queue-head label-skipped-head
     label-load-skipped label-skip label-queue-update]]
   [sysrev-web.ajax :as ajax]))

;; Initialize the label queue after other data has been loaded
(add-watch
 server-data :initial-label-queue
 (fn [k v old new]
   (when (= (current-page) :classify)
     (when (and (empty? (label-queue))
                (not (data-initialized? :classify old))
                (data-initialized? :classify new))
       (label-queue-update)))))

(defn classify-page []
  (let [user-id (current-user-id)
        article-id (label-queue-head)
        criteria (:criteria @server-data)
        criteria-ids (keys criteria)
        overall-cid (:overall-cid @server-data)]
    [:div.ui.grid
     [article-info-component article-id false]
     [label-editor-component
      (fn [cid new-value]
        (swap! state assoc-in
               [:page :classify :label-values cid] new-value)
        (->> (get-in @state [:page :classify :label-values])
             (ajax/send-tags article-id)))
      (get-in @state [:page :classify :label-values])]
     [:div.ui.row
      [:div.ui.five.wide.column]
      [:div.ui.six.wide.column.center.aligned
       [:div.ui.primary.right.labeled.icon.button
        {:on-click #(do (label-skip)
                        (ajax/pull-user-info user-id))}
        "Continue"
        [:i.right.circle.arrow.icon]]
       [:div.ui.secondary.right.labeled.icon.button
        {:class
         (if (nil? (get-in @state [:page :classify :label-values overall-cid]))
           "disabled"
           "")}
        ;; {:on-click label-skip}
        "Finalize..."
        [:i.check.circle.outline.icon]]]
      (let [n-unconfirmed
            (count
             (get-in @server-data [:users user-id :labels :unconfirmed]))
            n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
        [:div.ui.five.wide.column
         [:div.ui.buttons.right.floated
          [:div.ui.labeled.button
           {:on-click #(nav-scroll-top (str "/user/" user-id))}
           [:div.ui.green.button
            (str "Review and confirm... ")]
           [:a.ui.label n-str nbsp [:i.file.text.icon]]]]])]]))
