(ns sysrev.ui.sysrev
  (:require [sysrev.base :refer [st work-state]]
            [sysrev.ajax :refer
             [send-file-url pull-files delete-file get-file get-file-url]]
            [sysrev.state.core :as st :refer [data]]
            [sysrev.state.project :as project
             :refer [project project-admin?]]
            [sysrev.state.settings :as settings]
            [sysrev.state.labels :as labels]
            [sysrev.util :refer
             [nav number-to-word full-size?]]
            [sysrev.shared.util :refer [in? num-to-english]]
            [sysrev.ui.components :refer
             [true-false-nil-tag with-tooltip selection-dropdown]]
            [sysrev.ui.labels :refer [labels-page]]
            [sysrev.ui.upload :refer [upload-container basic-text-button]]
            [sysrev.ui.article-list :refer [select-answer-status]]
            [reagent.core :as r]
            [sysrev.shared.predictions :as predictions]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [sysrev.ui.charts :refer
             [chart-container line-chart bar-chart pie-chart]]
            [goog.string :as string]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]
            [sysrev.ajax :as ajax])
  (:require-macros
   [sysrev.macros :refer [with-mount-hook using-work-state]]))

(defn user-info-card [user-id]
  (let [{:keys [email name]} (data [:users user-id])
        display-name (or name email)
        {:keys [articles in-progress]} (project :members user-id)
        num-include (-> articles :includes count)
        num-exclude (-> articles :excludes count)
        num-classified (+ num-include num-exclude)]
    [:div
     {:style {:margin-top "10px"
              :margin-bottom "0px"}}
     [:div.ui.top.attached.header.segment
      {:style {:padding-left "0.7em"
               :padding-right "0.7em"}}
      [:a {:href (str "/user/" user-id)}
       [:h4 display-name]]]
     [:table.ui.bottom.attached.unstackable.table
      {:style {:margin-left "-1px"}}
      [:thead
       [:tr
        [:th "Confirmed"]
        [:th "Included"]
        [:th "Excluded"]
        [:th "In progress"]]]
      [:tbody
       [:tr
        [:td [:span.attention (str num-classified)]]
        [:td [:span.attention (str num-include)]]
        [:td [:span.attention (str num-exclude)]]
        [:td [:span.attention (str in-progress)]]]]]]))

(defn selected-label-id []
  (or (st :page :project :active-label-id)
      (project :overall-label-id)))

(defn nav-article-status [status]
  (nav "/project/articles")
  (select-answer-status status))

(defn- chart-value-labels [entries]
  [:div.ui.one.column.center.aligned.middle.aligned.grid
   {:style {:padding-top "2em"
            :padding-botom "2em"}}
   (->>
    (partition-all 4 entries)
    (map-indexed
     (fn [i row]
       [:div.ui.middle.aligned.column
        {:key (str i)
         :style {:padding-right "2em"
                 :padding-left "0.5em"}}
        [:div.ui.middle-aligned.list
         (->>
          row
          (map-indexed
           (fn [i [value color label status]]
             [:div.item
              {:key (str i)}
              [:div.ui.fluid.basic.button
               {:style {:padding "7px"
                        :margin "4px"
                        :border (str "1px solid " color)}
                :on-click #(nav-article-status status)}
               [:span (str "View " label " (" value ")")]]]))
          doall)]]))
    doall)])

(defn project-summary-box []
  (let [stats (project :stats)
        total (-> stats :articles)
        reviewed (-> stats :labels :any)
        unreviewed (- total reviewed)
        single (-> stats :labels :single)
        double (-> stats :labels :double)
        pending (-> stats :conflicts :pending)
        resolved (-> stats :conflicts :resolved)
        consistent (- double pending)
        colors {:grey "rgba(160,160,160,0.5)"
                :green "rgba(20,200,20,0.5)"
                :red "rgba(220,30,30,0.5)"
                :blue "rgba(30,100,230,0.5)"
                :purple "rgba(146,29,252,0.5)"}]
    [:div.project-summary
     [:div.ui.grey.segment
      [:h4.ui.center.aligned.dividing.header
       (str reviewed " articles reviewed of " total " total")]
      [:div.ui.two.column.stackable.grid.pie-charts
       (let [entries
             [["Single" single :single]
              ["Double" consistent :consistent]
              ["Conflicting" pending :conflict]
              ["Resolved" resolved :resolved]]
             labels (mapv #(nth % 0) entries)
             values (mapv #(nth % 1) entries)
             statuses (mapv #(nth % 2) entries)
             view-status (fn [status]
                           (nav "/project/articles")
                           (select-answer-status status))
             on-click #(view-status (nth statuses %))]
         [:div.row
          [:div.column
           [chart-container pie-chart labels values on-click
            (map colors [:blue :green :red :purple])]]
          [:div.column
           [chart-value-labels
            [[single (:blue colors) "single-reviewed" :single]
             [consistent (:green colors) "double-reviewed" :consistent]
             [pending (:red colors) "conflicting" :conflict]
             [resolved (:purple colors) "resolved" :resolved]]]]])]]]))

(defn label-counts-box []
  (let [stats (project :stats)]
    [:table.ui.celled.unstackable.table.grey.segment
     [:thead
      [:tr
       [:th "Criteria label counts"]
       [:th (true-false-nil-tag
             "large" nil true "Include" true false)]
       [:th (true-false-nil-tag
             "large" nil true "Exclude" false false)]
       #_
       [:th (true-false-nil-tag
             "large" nil false "Unknown" nil false)]]]
     [:tbody
      (doall
       (for [{:keys [label-id short-label category]}
             (labels/project-labels-ordered)]
         (when (= category "inclusion criteria")
           (let [counts (get-in stats [:inclusion-values label-id])]
             ^{:key {:label-stats label-id}}
             [:tr
              [:td short-label]
              [:td (str (get counts :true))]
              [:td (str (get counts :false))]
              #_ [:td (str (get counts :nil))]]))))]]))

(defn member-list-box []
  (let [user-ids (project/project-member-user-ids false)]
    [:div.ui.grey.segment
     [:h4 {:style {:margin-bottom "0px"}}
      "Project members"]
     (doall
      (->> user-ids
           (map
            (fn [user-id]
              ^{:key {:user-info user-id}}
              [user-info-card user-id]))))]))


(defn show-email [email] (-> email (clojure.string/split "@") first))
(defn user-summary-chart []
  (let [user-ids (project/project-member-user-ids false)
        users (mapv #(data [:users %]) user-ids)
        user-articles (->> user-ids (mapv #(->> % (project :members) :articles)))
        xs (mapv (fn [{:keys [email name]}] (or name (show-email email))) users)
        includes (mapv #(-> % :includes count) user-articles)
        excludes (mapv #(-> % :excludes count) user-articles)
        yss [includes excludes]
        ynames ["include" "exclude"]]
    [:div.ui.grey.segment
     [:h4.ui.dividing.header
      "Member activity"]
     [chart-container bar-chart xs ynames yss]]))

(defn project-page-menu-full [active-tab]
  (let [make-class #(if (= % active-tab) "active item" "item")
        has-predict-data (not-empty (project :stats :predict :confidences))
        make-tab (fn [tab path label]
                   ^{:key {:menu-tab tab}}
                   [:a {:class (make-class tab) :href path}
                    [:h4.ui.header label]])
        n-tabs (+ 5
                  (if has-predict-data 1 0)
                  #_ (if (project-admin?) 1 0))]
    [:div.ui
     {:class
      (str (num-to-english n-tabs)
           " item secondary pointing menu project-menu")}
     [make-tab :overview "/project" "Project overview"]
     [make-tab :articles "/project/articles" "Articles"]
     [make-tab :user-profile "/user" "User"]
     [make-tab :labels "/project/labels" "Label definitions"]
     (when has-predict-data
       [make-tab :predict "/project/predict" "Prediction"])
     #_
     (when (project-admin?)
       [make-tab :settings "/project/settings" "Settings"])
     [:a
      {:class (make-class :classify) :href "/project/classify"}
      [:div.ui.large.basic.button.classify "Classify"]]]))

(defn project-page-menu-mobile [active-tab]
  (let [make-class #(if (= % active-tab) "active item" "item")
        has-predict-data (not-empty (project :stats :predict :confidences))
        make-tab (fn [tab path label]
                   ^{:key {:menu-tab tab}}
                   [:a {:class (make-class tab) :href path}
                    [:h4.ui.header label]])
        n-tabs (+ 5
                  #_ (if has-predict-data 1 0)
                  #_ (if (project-admin?) 1 0))]
    [:div.ui
     {:class
      (str (num-to-english n-tabs)
           " item secondary pointing menu project-menu")}
     [make-tab :overview "/project" "Overview"]
     [make-tab :articles "/project/articles" "Articles"]
     [make-tab :user-profile "/user" "User"]
     [make-tab :labels "/project/labels" "Labels"]
     #_
     (when has-predict-data
       [make-tab :predict "/project/predict" "Prediction"])
     #_
     (when (project-admin?)
       [make-tab :settings "/project/settings" "Settings"])
     [:a
      {:class (make-class :classify) :href "/project/classify"}
      [:div.ui.basic.button.classify "Classify"]]]))

(defn project-page-menu [active-tab]
  (if (full-size?)
    [project-page-menu-full active-tab]
    [project-page-menu-mobile active-tab]))


(def file-types {"doc" "word"
                 "docx" "word"
                 "pdf" "pdf"
                 "xlsx" "excel"
                 "xls" "excel"
                 "gz" "archive"
                 "tgz" "archive"
                 "zip" "archive"})

(defn get-file-class [fname]
  (get file-types (-> fname (.split ".") last) "text"))


(defonce editing-files (r/atom false))
(defn toggle-editing [] (swap! editing-files not))

(defn project-files-box []
  (letfn [(show-date [file]
            (let [date (tf/parse (:upload-time file))
                  parts (mapv #(% date) [t/month t/day t/year])]
              (apply goog.string/format "%d/%d/%d" parts)))]
    [:div.ui.grey.segment
     [:h4.ui.dividing.header "Project resources"]
     (when-let [files (:files (project))]
       [:div.ui.celled.list
        (doall
         (->>
          files
          (map
           (fn [file]
             [:div.icon.item {:key (:file-id file)}
              (if @editing-files
                [:i.ui.small.middle.aligned.red.delete.icon {:on-click #(delete-file (:file-id file))}]
                [:i.ui.outline.blue.file.icon {:class (get-file-class (:name file))}])
              [:div.content {:on-click #(get-file (:file-id file) (:name file))}
               [:a.header {:href (get-file-url (:file-id file) (:name file))} (:name file)]
               [:div.description (show-date file)]]]))))])
     [:div.ui.container
      [upload-container basic-text-button send-file-url pull-files "Upload Project Document"]
      [:div.ui.right.floated.basic.icon.button {:on-click toggle-editing :class (when @editing-files "red")}
       [:i.ui.small.small.blue.pencil.icon]]]]))

(defn invite-link-segment []
  [:div.ui.grey.segment
   ;; {:style {:padding "10px"}}
   [:div.ui.fluid.labeled.input
    [:div.ui.label "Project invite link"]
    [:input.ui.input
     {:readOnly true
      :value (project/project-invite-url (st/current-project-id))}]]])

(defn open-settings-box []
  [:div.ui.grey.segment
   [:a.ui.fluid.right.labeled.icon.button {:href "/project/settings"}
    [:i.settings.icon]
    "Project settings"]])

(defn project-overview-box []
  [:div.ui.two.column.stackable.grid.project-overview
   [:div.ui.row
    [:div.ui.column
     [project-summary-box]
     [project-files-box]
     #_
     [label-counts-box]]
    [:div.ui.column
     [user-summary-chart]
     [open-settings-box]
     [invite-link-segment]
     #_ [member-list-box]]]])

(defn train-input-summary-box []
  (let [label-id (selected-label-id)
        short-label (project :labels label-id :short-label)
        counts (project :stats :predict label-id :counts)]
    [:table.ui.celled.unstackable.table.grey.segment.mobile-table
     [:thead
      [:tr
       [:th "All articles"]
       [:th "Unlabeled articles"]
       [:th "Labeled " [:code "true"]]
       [:th "Labeled " [:code "false"]]]]
     [:tbody
      [:tr
       [:td (:total counts)]
       [:td (:unlabeled counts)]
       [:td (:include-label counts)]
       [:td (:exclude-label counts)]]]]))

(defn value-confidence-box []
  (let [label-id (selected-label-id)
        c-include (project :stats :predict label-id :include :confidence)
        c-exclude (project :stats :predict label-id :exclude :confidence)
        c-percents (-> c-include keys sort)]
    [:div.ui.grid
     [:div.ui.sixteen.wide.column
      [:div.ui.top.attached.grey.segment
       [:h4 "Article count by label probability"]]
      [:table.ui.celled.unstackable.bottom.attached.table.mobile-table
       [:thead
        [:tr
         [:th]
         (doall
          (for [percent c-percents]
            ^{:key {:confidence-header [label-id percent]}}
            [:th (str ">= " percent "%")]))]]
       [:tbody
        [:tr
         [:td
          [:code (if (full-size?) "Pr(true)" "true")]]
         (doall
          (for [percent c-percents]
            (let [n-articles (get c-include percent)]
              ^{:key {:confidence-count [:include label-id percent]}}
              [:td (str n-articles)])))]
        [:tr
         [:td
          [:code (if (full-size?) "Pr(false)" "false")]]
         (doall
          (for [percent c-percents]
            (let [n-articles (get c-exclude percent)]
              ^{:key {:confidence-count [:exclude label-id percent]}}
              [:td (str n-articles)])))]]]]]))

(defn predict-report-labels-menu []
  (let [predict-ids (keys (project :stats :predict))
        label-ids (->> (labels/project-labels-ordered)
                       (map :label-id)
                       (filter (in? predict-ids)))
        ncols (-> label-ids count number-to-word)
        active-label-id (selected-label-id)]
    [:div.ui.top.attached.disabled.segment
     [:h4
      "Select label: "
      (let [dropdown
            (with-mount-hook
              #(.dropdown (js/$ (r/dom-node %))))]
        [dropdown
         [:div.ui.small.blue.dropdown.button
          {:style {:margin-left "5px"}}
          [:input {:type "hidden" :name "menu-dropdown"}]
          [:label (project :labels active-label-id :short-label)]
          [:i.chevron.down.right.icon]
          [:div.menu
           (doall
            (for [label-id label-ids]
              (let [active (= label-id active-label-id)]
                ^{:key {:predict-menu-label label-id}}
                [:a.item
                 {:href (str "/project/predict/" (name label-id))
                  :class (if active "default active" "")}
                 (project :labels label-id :short-label)])))]]])]]))


(defn project-predict-report-table [vs]
  (letfn [(coverage [v] (/ (predictions/examples v) (:global-population-size v)))
          (mk-coverage-string [v]
            (string/format "%.1f%% (%d/%d)"
                           (* 100 (coverage v))
                           (predictions/examples v)
                           (:global-population-size v)))
          (conf-row [thresh]
            (let [v (conf-at-least thresh)]
              [:tr
               [:td "> " (-> thresh (* 100) (str "%"))]
               [:td
                (->> v predictions/true-positive-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-positives v) (predictions/condition-positives v))]
               [:td (->> v predictions/true-negative-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-negatives v) (predictions/condition-negatives v))]
               [:td (mk-coverage-string v)]]))
          (conf-row [thresh]
            (let [v (conf-at-least thresh)]
              [:tr
               [:td "> " (-> thresh (* 100) (str "%"))]
               [:td
                (->> v predictions/true-positive-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-positives v) (predictions/condition-positives v))]
               [:td (->> v predictions/true-negative-rate (* 100) (string/format "%.1f%%"))
                (string/format " (%d / %d)" (predictions/true-negatives v) (predictions/condition-negatives v))]
               [:td (mk-coverage-string v)]]))
          (conf-at-least [thresh] (->> vs (filterv #(> (:confidence %) thresh)) first :values))]
    [:table.ui.celled.table
     [:thead
      [:tr
       [:th "Confidence"]
       [:th "True Positive Rate (Sensitivity)"]
       [:th "True Negative Rate (Specificity)"]
       [:th "Coverage"]]]
     [:tbody
      [conf-row 0.9]
      [conf-row 0.95]
      [conf-row 0.99]]]))


(defn project-predict-report-box []
  (when-let [vs (project :stats :predict :confidences)]
    ;; Facilitate creation of multiple datasets, each as functions on the vector of confidence values.
    (letfn [(confs-by-f [& fs]
              (let [ffs (into [:confidence] (mapv #(comp % :values) fs))]
                (->> vs
                     (mapv (apply juxt ffs))
                     (filterv (comp (partial > 1.0) first)))))
            (coverage [v] (/ (predictions/examples v) (:global-population-size v)))]
      (let [data (confs-by-f predictions/positive-predictive-value predictions/negative-predictive-value coverage)
            confidences (->> data (map first) (map #(string/format "%.2f" %)))
            ppvs (mapv second data)
            npvs (mapv #(get % 2) data)
            coverages (mapv #(get % 3) data)]
        [:div
         [:div.ui.bottom.attached.segment
          [:div.ui.center.aligned.header "Prediction confidence report"]
          [:div.ui.segment
           [project-predict-report-table vs]]
          [:div.ui.segment
           [chart-container
            line-chart
            confidences
            ["Positive Predictive Value" "Negative Predictive Value" "Coverage"]
            [ppvs npvs coverages]]]]]))))

(defn project-options-box []
  (let [active (settings/active-settings)
        modified? (settings/settings-modified?)
        valid? (settings/valid-setting-inputs?)
        field-class (fn [skey]
                      (if (settings/valid-setting-input? skey)
                        "" "error"))]
    [:div.ui.grey.segment
     [:h4.ui.dividing.header "Configuration options"]
     [:form.ui.equal.width.form {:class (if valid? "" "warning")}
      (let [skey :second-review-prob]
        [:div.fields
         [:div.field {:class (field-class skey)}
          [with-tooltip
           [:label "Double-review priority "
            [:i.ui.large.grey.circle.question.mark.icon]]
           {:delay {:show 200
                    :hide 0}
            :hoverable false}]
          [:div.ui.popup.transition.hidden.tooltip
           [:p "Controls proportion of articles assigned for second review in Classify task."]
           [:p "0% will assign unreviewed articles whenever possible."]
           [:p "100% will assign for second review whenever possible."]]
          [:div.ui.right.labeled.input
           [:input
            {:type "text"
             :name (str skey)
             :value (settings/render-setting skey)
             :on-change
             #(->> % .-target .-value (settings/edit-setting skey))}]
           [:div.ui.basic.label "%"]]]
         [:div.field]])]
     [:div.ui.divider]
     [:div
      [:button.ui.primary.button
       {:class (if (and valid? modified?) "" "disabled")
        :on-click #(ajax/save-project-settings)}
       "Save changes"]
      [:button.ui.button
       {:class (if modified? "" "disabled")
        :on-click #(settings/reset-settings-fields)}
       "Reset"]]]))

(defn edit-permissions-form [permission]
  (let [active-ids (project/permission-members permission)
        selected-user nil
        select-user (fn [user-id] nil)]
    [:div
     [:div.ui.middle.aligned.divided.list
      (doall
       (for [user-id active-ids]
         [:div.item {:key user-id}
          [:div.right.floated.middle.aligned.content
           [:div.ui.tiny.button "Remove"]]
          [:i.ui.large.grey.user.icon]
          [:div.middle.aligned.content
           [:div.ui.basic.label
            (st/user-name-by-id user-id)]]]))]
     [:div.ui.divider]
     [:form.ui.form
      {:style {:width "100%"}}
      [:div.fields
       [:div.twelve.wide.field
        [selection-dropdown
         [:div.text
          (if selected-user
            (st/user-name-by-id selected-user)
            "Select user")]
         (->> (project/project-member-user-ids false)
              (remove (in? active-ids))
              (mapv
               (fn [user-id]
                 [:div.item
                  (into
                   {:key user-id
                    :on-click #(select-user user-id)}
                   (when (= user-id selected-user)
                     {:class "active selected"}))
                  (st/user-name-by-id user-id)])))]]
       [:div.four.wide.field
        [:div.ui.fluid.button "Add"]]]]]))

(defn project-permissions-box []
  [:div.ui.grey.segment
   [:h4.ui.dividing.header "User permissions"]
   [:div.ui.segment
    [:h5.ui.dividing.header "Admin"]
    [edit-permissions-form "admin"]]
   [:div.ui.segment
    [:h5.ui.dividing.header "Resolve conflicts"]
    [edit-permissions-form "resolve"]]])

(defn project-settings-page []
  [:div.ui.segment
   [:h3.ui.dividing.header "Project settings"]
   [:div.ui.two.column.stackable.grid.project-settings
    [:div.ui.row
     [:div.ui.column
      [project-options-box]]
     [:div.ui.column
      [project-permissions-box]]]]])

(defn project-page [active-tab content]
  (let [bottom-segment?
        (case active-tab
          :overview false
          true)]
    [:div.ui.container
     [:div.ui.top.attached.center.aligned.segment.project-header
      [:h5 (data [:all-projects (st/current-project-id) :name])]]
     [:div.ui.segment.project-segment
      {:class (if bottom-segment? "bottom attached" "attached")}
      [project-page-menu active-tab]
      [:div.padded content]]]))
