(ns sysrev-web.ui.classify
  (:require
   [sysrev-web.base :refer [state ga-event]]
   [sysrev-web.state.core :refer [current-user-id current-page]]
   [sysrev-web.util :refer [scroll-top nav-scroll-top nbsp full-size?]]
   [sysrev-web.routes :refer [data-initialized?]]
   [sysrev-web.ui.components :refer
    [three-state-selection with-tooltip confirm-modal-box]]
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
          criteria (d/project :criteria)
          criteria-ids (keys criteria)
          overall-cid (d/project :overall-cid)]
      [:div.ui
       [article-info-component
        article-id false user-id (data :classify-review-status) true]
       [label-editor-component
        article-id [:page :classify :label-values]]
       [confirm-modal-box
        #(data :classify-article-id)
        [:page :classify :label-values]
        (fn [] (scroll-top))]
       (if (full-size?)
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
                "")
              :on-click #(do (.modal (js/$ ".ui.modal") "show"))}
             "Confirm labels"
             [:i.check.circle.outline.icon]]
            [:div.ui.right.labeled.icon.button
             {:on-click
              #(do (ga-event "labels" "next_article")
                   (ajax/fetch-classify-task true)
                   (ajax/pull-member-labels user-id)
                   (scroll-top))}
             "Next article"
             [:i.right.circle.arrow.icon]]]
           (let [n-unconfirmed
                 (count
                  (d/project [:labels user-id :unconfirmed]))
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
                   (get (d/active-label-values
                         article-id [:page :classify :label-values])
                        overall-cid))
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
                   (d/project [:labels user-id :unconfirmed]))
                  n-str (if (zero? n-unconfirmed) "" (str n-unconfirmed " "))]
              [:div.ui.small.green.icon.button.middle.aligned
               {:on-click #(nav-scroll-top (str "/user/" user-id))
                :class (if (= 0 n-unconfirmed) "disabled" "")
                :style {:float "right"}}
               n-str [:i.small.file.text.right.icon]])]]])])))
