(ns sysrev.views.panels.project.article-list
  (:require
   [clojure.string :as str]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
   [reagent.ratom :refer [reaction]]
   [sysrev.views.base :refer [panel-content logged-out-content]]
   [sysrev.views.components :refer [selection-dropdown]]
   [sysrev.subs.label-activity :refer [answer-statuses]]
   [sysrev.util :refer [nbsp full-size? number-to-word]]
   [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel-name [:project :project :articles])

(reg-sub
 ::label-id
 (fn []
   [(subscribe [:panel-field [:label-id] panel-name])
    (subscribe [:project/overall-label-id])])
 (fn [[label-id default]]
   (or label-id default)))
(reg-event-fx
 ::set-label-id
 [trim-v]
 (fn [_ [label-id]]
   {:dispatch-n
    (list [:set-panel-field [:label-id] label-id panel-name]
          [::set-answer-value nil])}))

(reg-sub
 ::answer-status
 :<- [:panel-field [:answer-status] panel-name]
 (fn [status] status))
(reg-event-fx
 ::set-answer-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:set-panel-field [:answer-status] status panel-name]}))

(reg-sub
 ::answer-value
 :<- [:panel-field [:answer-value] panel-name]
 (fn [value] value))
(reg-event-fx
 ::set-answer-value
 [trim-v]
 (fn [_ [value]]
   {:dispatch [:set-panel-field [:answer-value] value panel-name]}))

(reg-event-fx
 ::reset-filters
 [trim-v]
 (fn [_ [keep]]
   {:dispatch-n
    (->> (list (when-not (in? keep :label-id)
                 [::set-label-id nil])
               (when-not (in? keep :answer-status)
                 [::set-answer-status nil])
               (when-not (in? keep :answer-value)
                 [::set-answer-value nil]))
         (remove nil?))}))

(reg-sub-raw
 ::articles
 (fn [_ _]
   (reaction
    (when-let [label-id @(subscribe [::label-id])]
      @(subscribe
        [:label-activity/articles
         label-id {:answer-status @(subscribe [::answer-status])
                   :answer-value @(subscribe [::answer-value])}])))))

(defn label-selector []
  (let [active-id @(subscribe [::label-id])]
    [selection-dropdown
     [:div.text @(subscribe [:label/display active-id])]
     (->> @(subscribe [:project/label-ids])
          (mapv
           (fn [label-id]
             [:div.item
              (into {:key label-id
                     :on-click #(dispatch [::set-label-id label-id])}
                    (when (= label-id active-id)
                      {:class "active selected"}))
              @(subscribe [:label/display label-id])])))]))

(defn answer-status-selector []
  (let [active-status @(subscribe [::answer-status])
        status-name #(if (nil? %) "<Any>" (-> % name str/capitalize))]
    [selection-dropdown
     [:div.text (status-name active-status)]
     (->> (concat [nil] answer-statuses)
          (mapv
           (fn [status]
             [:div.item
              (into {:key status
                     :on-click #(dispatch [::set-answer-status status])}
                    (when (= status active-status)
                      {:class "active selected"}))
              (status-name status)])))]))

(defn answer-value-selector []
  (let [label-id @(subscribe [::label-id])]
    (let [all-values @(subscribe [:label/all-values label-id])
          active-value @(subscribe [::answer-value])]
      [selection-dropdown
       [:div.text
        (if (nil? active-value) "<Any>" (str active-value))]
       (vec
        (concat
         [[:div.item {:on-click #(dispatch [::set-answer-value nil])}
           "<Any>"]]
         (->> all-values
              (mapv
               (fn [value]
                 [:div.item
                  (into {:key (str value)
                         :on-click #(dispatch [::set-answer-value value])}
                        (when (= value active-value)
                          {:class "active selected"}))
                  (str value)])))))])))

(defn article-filter-form []
  (when-let [label-id @(subscribe [::label-id])]
    (let [all-values @(subscribe [:label/all-values label-id])
          n-columns (+ 3 3 (if (empty? all-values) 0 3) 2)
          whitespace-columns (- 16 n-columns)]
      [:div.ui.secondary.segment
       {:style {:padding "10px"}}
       [:div.ui.small.dividing.header "Article filters"]
       [:form.ui.form
        [:div.field
         [:div.fields
          [:div.ui.small.three.wide.field
           [:label "Label"]
           [label-selector]]
          [:div.ui.small.three.wide.field
           [:label "Answer status"]
           [answer-status-selector]]
          (when-not (empty? all-values)
            [:div.ui.small.three.wide.field
             [:label "Answer value"]
             [answer-value-selector]])
          (when (full-size?)
            [:div {:class (str (number-to-word whitespace-columns)
                               " wide field")}])
          [:div.ui.small.two.wide.field
           [:label nbsp]
           [:div.ui.button
            {:on-click #(dispatch [::reset-filters])}
            "Reset filters"]]]]]])))

(defn article-list-view []
  (when-let [label-id @(subscribe [::label-id])]
    (with-loader [[:project/label-activity label-id]] {}
      (let [articles @(subscribe [::articles])]
        [:div
         [:p (str "Found " (count articles) " articles")]]))))

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    [:div
     [article-filter-form]
     [article-list-view]]))
