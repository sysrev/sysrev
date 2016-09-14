(ns sysrev-web.ui.classify
  (:require
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.core :refer [current-user-id current-page]]
   [sysrev-web.util :refer [nav-scroll-top nbsp]]
   [sysrev-web.routes :refer [data-initialized?]]
   [sysrev-web.ui.components :refer
    [three-state-selection with-tooltip]]
   [sysrev-web.ui.article :refer
    [article-info-component label-editor-component]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.state.data :as d :refer [data]])
  (:require-macros [sysrev-web.macros :refer [with-state]]))

;; Initialize the label queue after other data has been loaded
#_
(add-watch
 state :initial-label-queue
 (fn [k v old new]
   (when (= (with-state new (current-page)) :classify)
     (when (and (empty? (with-state new (label-queue)))
                (not (with-state old (data-initialized? :classify)))
                (with-state new (data-initialized? :classify)))
       (label-queue-update)))))

(defn classify-page []
  (when-let [article-id (data :classify-article-id)]
    (let [user-id (current-user-id)
          ;;article-id (label-queue-head)
          criteria (-> @state :data :criteria)
          criteria-ids (keys criteria)
          overall-cid (-> @state :data :overall-cid)]
      [:div.ui
       [article-info-component article-id false]
       [label-editor-component
        article-id [:page :classify :label-values]]
       #_
       [label-editor-component
        (fn [cid new-value]
          (swap! state assoc-in
                 [:page :classify :label-values cid] new-value)
          (->> (get-in @state [:page :classify :label-values])
               (ajax/send-tags article-id)))
        (get-in @state [:page :classify :label-values])]
       [:div.ui.grid
        [:div.ui.row
         [:div.ui.five.wide.column]
         [:div.ui.six.wide.column.center.aligned
          [:div.ui.primary.right.labeled.icon.button
           {:class
            (if (nil?
                 (get (d/active-label-values
                       article-id [:page :classify :label-values])
                      overall-cid))
              "disabled"
              "")}
           "Finalize..."
           [:i.check.circle.outline.icon]]
          [:div.ui.secondary.right.labeled.icon.button
           {:on-click #(do (ajax/fetch-classify-task true)
                           (ajax/pull-user-info user-id))}
           "Next article"
           [:i.right.circle.arrow.icon]]]
         (let [n-unconfirmed
               (count
                (get-in @state [:data :users user-id :labels :unconfirmed]))
               n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
           [:div.ui.five.wide.column
            [:div.ui.buttons.right.floated
             [:div.ui.labeled.button
              {:on-click #(nav-scroll-top (str "/user/" user-id))}
              [:div.ui.green.button
               (str "Review and confirm... ")]
              [:a.ui.label n-str nbsp [:i.file.text.icon]]]]])]]])))
