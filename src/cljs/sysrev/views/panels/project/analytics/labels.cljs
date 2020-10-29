(ns sysrev.views.panels.project.analytics.labels
  (:require [clojure.set :as set]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [sysrev.data.core :refer [def-data reload]]
            [sysrev.chartjs :as chartjs]
            [sysrev.views.semantic :refer [Grid Column Button]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.charts :as charts]
            [sysrev.views.panels.project.articles :refer [load-settings-and-navigate]]
            [sysrev.views.panels.project.analytics.common :refer [BetaMessage]]
            [sysrev.util :as util :refer [format round ellipsize sum]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :labels])

;;; UTILITIES
(defn- get-default [db key default-val]
  (as-> (get db key) x
    (if (nil? x) default-val x)))

(defn- SetSubscriptionButton [sub-evt-name label value &
                              {:keys [color title txt-col type]}]
  (let [curset @(subscribe [sub-evt-name])]
    [(case (or type "radio")
       "radio"    ui/RadioButton
       "checkbox" ui/CheckboxButton)
     {:text label
      :title title
      :active? (contains? curset value)
      :on-click #(dispatch [sub-evt-name {:value value :curset curset}])}]))

(defn- ButtonArray [labels values sub-evt-name &
                    {:keys [colors titles txt-col type]}]
  [:div.inline
   (for [i (range (count labels))]
     (let [label   (some-> labels (get i))
           value   (some-> values (get i))
           color   (some-> colors (get i))
           title   (some-> titles (get i))
           txt-col (some-> txt-col (get i))] ^{:key (str i value)}
       [SetSubscriptionButton sub-evt-name label value
        :color color :title title :txt-col txt-col :type type]))])

;;; STATISTICS EVENTS / SUBSCRIPTIONS
(def-data :project/analytics-counts
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? ::count-data)))
  :uri (fn [_] "/api/countgroup")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] result]
             {:db (assoc-in db [:data :project project-id ::count-data] result)})
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:db (assoc-in db [:data :project project-id ::count-data] {:error error})}))

(reg-sub :project/analytics-counts
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (::count-data project)))

(reg-sub ::sorted-user-counts
         :<- [:project/analytics-counts]
         (fn [{:keys [groups]}]
           (->> (for [{:keys [group articles]} groups]
                  (distinct (map (fn [g] {:user (:user g) :articles articles}) group)))
                flatten
                (group-by :user)
                (map (fn [[k v]] {:user k :count (sum (map :articles v))}))
                (sort-by #(- (:count %))))))

(reg-sub ::filtered-count-data
         :<- [:project/analytics-counts]  :<- [::filter-users]
         :<- [::filter-answers]           :<- [::article-type-selection]
         (fn [[count-data filter-users filter-answers filter-conc]]
           (let [answers (set (map (fn [x] {:label (:label-id x) :answer (:answer x)})
                                   filter-answers))
                 group-has-lab-ans? (fn [group]
                                      (->> (set (map #(select-keys % [:label :answer]) group))
                                           (set/subset? answers)))
                 filter-counts (cond->> (:groups count-data)
                                 (seq answers) (filter #(group-has-lab-ans? (:group %))))]
             (->> (for [{:keys [group articles]} filter-counts]
                    {:group (filter #(and (contains? filter-users (:user %))
                                          (contains? filter-conc (:concordant %)))
                                    group)
                     :articles articles})
                  (remove #(empty? (:group %)))))))

(reg-sub ::filter-label-counts
         :<- [::filtered-count-data]
         (fn [filtered-count-data]
           (letfn [ ;; a map of [(label,answer),{:articles num :answers num}]
                   (group-map [group articles]
                     (->> (map #(select-keys % [:label :answer]) group)
                          (reduce (fn [lamap lblans]
                                    (let [{:keys [answers]} (get lamap lblans
                                                                 {:articles 0 :answers 0})]
                                      (assoc lamap lblans {:articles articles
                                                           :answers (+ articles answers)})))
                                  {})))]
             (reduce (fn [lamap {:keys [group articles]}]
                       (->> (group-map group articles)
                            (reduce (fn [curmap [k {:keys [articles answers]}]]
                                      (let [{oldarts :articles oldans :answers}
                                            (get curmap k {:articles 0 :answers 0})]
                                        (assoc curmap k {:articles (+ oldarts articles)
                                                         :answers (+ oldans answers)})))
                                    lamap)))
                     {} filtered-count-data))))

(reg-sub ::user-counts
         :<- [::filtered-count-data]
         (fn [filter-counts]
           (let [entries (flatten (for [{:keys [group articles]} filter-counts]
                                    (for [{:keys [user]} group]
                                      {:user user :articles articles})))]
             (->> (for [user (distinct (map :user entries))]
                    [user (sum (map :articles
                                    (filter #(= (:user %) user) entries)))])
                  (sort-by second >)))))

(reg-sub ::combined-label-counts
         :<- [:project/label-ids] :<- [:project/label-counts] :<- [::filter-label-counts]
         (fn [[label-ids chart-counts filtered-counts]]
           (->> (for [{:keys [label-id value] :as row} chart-counts]
                  (let [key {:label (str label-id) :answer (str value)}]
                    (merge row (get filtered-counts key {:articles 0 :answers 0}))))
                (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids))
                           (:label-id %))))))

(reg-sub ::overall-counts
         :<- [::user-counts] :<- [::combined-label-counts] :<- [:project/article-counts]
         (fn [[user-counts label-counts {:keys [reviewed]}]]
           (let [all-articles     (map :articles label-counts)
                 all-answers      (map :answers label-counts)
                 all-label-ids    (distinct (map :label-id label-counts))
                 all-label-counts (map :count label-counts)]
             {:users (count user-counts)
              :labels (count all-label-ids)
              :answers (sum all-label-counts)
              :filtered-answers (sum all-answers)
              :filtered-articles (sum all-articles)
              :max-filtered-answers (reduce max all-answers)
              :max-filtered-articles (reduce max all-articles)
              :reviewed-articles reviewed
              :max-label-count (reduce max all-label-counts)
              :max-label-article-count (reduce max all-articles)})))

;; CONTROL EVENTS / SUBSCRIPTIONS
(defn- set-event
  "Function for creating 'set-events' which add or subtract values from an app-db set"
  [db-key exclusive]
  (fn [db [_ {:keys [value curset]}]]
    (assoc db db-key
           (cond (nil? value)              #{}
                 (set? value)              value
                 (contains? curset value)  (disj curset value)
                 exclusive                 #{value}
                 :else                     (conj curset value)))))

(reg-sub ::article-type-selection #(get-default % ::article-type-selection
                                                #{"Single" "Concordant" "Discordant"}))
(reg-event-db ::article-type-selection (set-event ::article-type-selection false))

(reg-sub ::count-type #(get-default % ::count-type #{"Count Every Answer"}))
(reg-event-db ::count-type
              (fn [db [_ {:keys [value]}]]
                (assoc db ::count-type (if (= value "Count Every Answer")
                                         #{value}
                                         #{"Once Per Article"}))))

(reg-sub ::filter-users #(get-default % ::filter-users #{}))
(reg-event-db ::filter-users
              (fn [db [_ {:keys [value curset]}]]
                (assoc db ::filter-users
                       (cond (nil? value)              #{}
                             (set? value)              value
                             (contains? curset value)  (disj curset value)
                             :else                     (conj curset value)))))

(reg-sub ::filter-answers #(get-default % ::filter-answers #{}))
(reg-event-db ::filter-answers (set-event ::filter-answers false))

;;; CHART
(reg-sub ::x-axis-selection #(get-default % ::x-axis-selection #{"Static"}))
(reg-event-db ::x-axis-selection
              (fn [db [_ {:keys [value]}]]
                (assoc db ::x-axis-selection
                       (if (= value "Dynamic") #{"Dynamic"} #{"Static"}))))

(reg-sub ::x-axis-options
         :<- [::count-type] :<- [::overall-counts] :<- [::x-axis-selection]
         (fn [[count-type overall-count x-axis-dynamic]]
           (let [font (charts/graph-font-settings)
                 every-answer? (contains? count-type "Count Every Answer")
                 max-dynamic-count (if every-answer?
                                     (:max-filtered-answers overall-count)
                                     (:max-filtered-articles overall-count))
                 max-static-count  (if every-answer?
                                     (:max-label-count overall-count)
                                     (:reviewed-articles overall-count))
                 max-count         (if (contains? x-axis-dynamic "Dynamic")
                                     max-dynamic-count max-static-count)]
             {:x {:scaleLabel {:display true
                               :labelString (if every-answer? "User Answers" "Articles")}
                  :stacked false
                  :suggestedMin 0
                  :suggestedMax max-count
                  :ticks font
                  :gridLines {:color (charts/graph-border-color)}}})))

(defn- y-axis [font]
  {:y {:stacked true
       :scaleLabel font
       :ticks (merge font {:padding 7})
       :gridLines {:drawTicks false :color (charts/graph-border-color)}}})

(defn- on-click-chart [entries]
  (fn [_e elts]
    (when-let [idx (and (pos-int? (.-length elts))
                        (-> elts (aget 0) .-index))]
      (when (and (nat-int? idx) (< idx (count entries)))
        (let [{:keys [label-id value short-label]} (nth entries idx)]
          (dispatch [::filter-answers
                     {:value {:label-id (str label-id) :name short-label
                              :answer (str value) :raw-answer value}
                      :curset @(subscribe [::filter-answers])}]))))))

(defn- LabelCountChart []
  (let [label-ids      @(subscribe [:project/label-ids])
        combined       @(subscribe [::combined-label-counts])
        count-type     @(subscribe [::count-type])
        font           (charts/graph-font-settings)
        max-length     (if (util/mobile?) 16 22)
        counts         (mapv (condp #(contains? %2 %1) count-type
                               "Count Every Answer" :answers
                               "Once Per Article"   :articles
                               (constantly 0)) combined)
        colors         (mapv :color combined)
        dss            [{:data (if (empty? counts) [0] counts)
                         :backgroundColor (if (empty? counts) ["#000000"] colors)
                         :maxBarThickness 12}]
        legend-labels  (->> (group-by :short-label combined)
                            (mapv (fn [[k [v & _]]]
                                    {:text k :fillStyle (:color v) :lineWidth 0})))
        options        (charts/wrap-default-options
                        {:scales (merge @(subscribe [::x-axis-options]) (y-axis font))
                         :legend {:labels (merge font {:generateLabels #(clj->js legend-labels)})}
                         :onClick (on-click-chart combined)
                         :tooltips {:mode "y"}}
                        :animate? false
                        :items-clickable? true)
        labels         (->> combined (map :value) (map str) (mapv #(ellipsize % max-length)))
        height         (* 2 (+ 40
                               (* 10 (round (/ (inc (count label-ids)) 3)))
                               (* 10 (count counts))))]
    [chartjs/horizontal-bar {:data {:labels labels :datasets dss}
                             :options options
                             :height height}]))

(defn- LabelCounts []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/label-counts project-id]] {}
      [LabelCountChart])))

;;; CONTROLS
(defn- Header []
  (let [{:keys [sampled]} @(subscribe [:project/analytics-counts])
        {:keys [users answers reviewed-articles filtered-answers]}
        @(subscribe [::overall-counts])]
    [:div.ui.segment
     [:h2 {:style {:margin-bottom "0em"}} (str "Label Counts")]

     (if (> answers 0)
       [:div {:style {:text-align "center" :margin "0.5em 0"}}
        [:p.bold {:id "answer-count" :style {:margin "0.25em"}}
         (when reviewed-articles (str reviewed-articles " articles with "))
         (str answers " answers total")]
        [:p.bold (format "%s selected users with %s filtered answers"
                         users filtered-answers)]]
       [:span "Label count analytics helps you understand label answer distributions.
     Get started reviewing to view some charts."])
     (when sampled
       [:div.ui.message
        [:div.header "This is a large project."]
        [:div.content "A random sample was taken to keep analytics fast. "]])
     [:div {:style {:margin-bottom "-0.5em"}}
      [BetaMessage]]]))

(defn- StepTitle [n title-content]
  [:h5 {:style {:margin-bottom "0px"}}
   (format "%d. " n) title-content])

(defn- StepSegment [n title content]
  [:div.ui.segment.concordance
   [StepTitle n title]
   content])

(defn- StepCountMethod [step-count]
  [StepSegment step-count "Select Counting Method"
   [:div
    [SetSubscriptionButton ::count-type "User Answers" "Count Every Answer"
     :title "Count all answers on each article. An article included by two reviewers adds 2 to the include bar."]
    [SetSubscriptionButton ::count-type "Articles" "Once Per Article"
     :title "Count answers once per article. An article included by 2 reviewers adds 1 to the include bar."]
    [:div {:style {:margin-top "0.5em"}}
     [:span "Counts "
      (if (contains? @(subscribe [::count-type]) "Count Every Answer")
        "each occurrence of the given user answer."
        "articles containing the given user answer.")]]]])

(reg-event-db ::scale-type (set-event ::scale-type true))
(reg-sub ::scale-type #(::scale-type %))

#_
(defn- StepChartScale [step-count]
  [:div
   [StepTitle step-count "Select Chart Scale"]
   [SetSubscriptionButton ::scale-type "Raw Count" "Raw Count"]
   [SetSubscriptionButton ::scale-type "Raw Percent" "Raw Percent"]
   [SetSubscriptionButton ::scale-type "Label Percent" "Label Percent"]
   [:div {:style {:margin-top "0.5em"}}
    (condp #(contains? %2 %1) @(subscribe [::scale-type])
      "Raw Count"      "Chart bars scaled to the filtered answer count."
      "Raw Percent"    "Chart bars scaled to the percent of all counted answers."
      "Label Percent"  "Chart bars scaled to percent of user answers with same label."
      "Select a chart scale")]])

(defn- StepReviewType [step-count]
  (let [article-type @(subscribe [::article-type-selection])]
    [StepSegment step-count "Filter By Concordance Type"
     [:div
      [SetSubscriptionButton ::article-type-selection
       "Single" "Single" :type "checkbox"]
      [SetSubscriptionButton ::article-type-selection
       "Concordant" "Concordant" :type "checkbox"]
      [SetSubscriptionButton ::article-type-selection
       "Discordant" "Discordant" :type "checkbox"]
      [:div {:style {:margin-top "0.5em"}}
       (case article-type
         #{"Single" "Concordant"}      "Count answers with 1+ agreeing users."
         #{"Single" "Discordant"}      "Count answers with 1 reviewer, or 2+ disagreeing reviewers."
         #{"Concordant" "Discordant"}  "Count answers with 2+ reviewers"
         #{"Single"}                   "Count answers with exactly one reviewer."
         #{"Concordant"}               "Count answers with 2+ reviewers who all agree."
         #{"Discordant"}               "Count answers with 2+ disagreeing reviewers."
         (if (= 3 (count article-type))
           "Count all article answers."
           "Filter answers by their article concordance."))]]]))

#_
(defn- user-counts-with-zeros []
  (let [selected-users @(subscribe [::filter-users])
        all-users (mapv :user @(subscribe [::sorted-user-counts]))
        user-counts (->> (mapv (fn [[usr cnt]]
                                 {:usr usr
                                  :cnt cnt
                                  :neg-cnt (* -1 cnt)
                                  :sel-usr (if (contains? selected-users usr) 0 1)})
                               @(subscribe [::user-counts]))
                         (sort-by (juxt :sel-usr :neg-cnt)))
        missing-users (set/difference (set all-users) (set (map :usr user-counts)))]
    (if (empty? missing-users)
      (map #(select-keys % [:usr :cnt]) user-counts)
      (concat (map #(select-keys % [:usr :cnt]) user-counts)
              (map (fn [usr] {:usr usr :cnt 0}) missing-users)))))

(defn- StepUserContentFilter [step-count]
  (let [selected-users @(subscribe [::filter-users])
        user-counts    @(subscribe [::sorted-user-counts])
        users          (mapv :user user-counts)
        usernames      (mapv (fn [uuid] @(subscribe [:user/display uuid])) users)
        max-count      (reduce max (map :count user-counts))
        colors         (mapv (fn [{:keys [user count]}]
                               (when-not (contains? (set selected-users) user)
                                 (format "rgba(98, 148, 175, %.2f)"
                                         (max 0.15 (* 0.7 (/ count max-count))))))
                             user-counts)
        titles         (mapv #(str (:count %) " answers") user-counts)
        inv-color      (if @(subscribe [:self/dark-theme?]) "white" "#282828")
        txt-col        (mapv #(if (contains? selected-users (:user %))
                                "white" inv-color)
                             user-counts)]
    [StepSegment step-count "Filter By User"
     [:div
      [ButtonArray usernames users ::filter-users
       :colors colors :titles titles :txt-col txt-col :type "checkbox"]
      [:div
       [Button {:size "mini" :style {:margin "2px" :margin-left "0px"}
                :primary (empty? selected-users)
                :on-click #(dispatch [::filter-users {:value #{} :curset #{}}])}
        "Select None"]
       [Button {:size "mini" :style {:margin "2px" :margin-left "0px"}
                :primary (empty? (set/difference (set users) selected-users))
                :on-click #(dispatch [::filter-users {:value (set users) :curset #{}}])}
        "Select All"]]
      [:div {:style {:margin-top "0.5em"}}
       (condp = (count selected-users)
         0  "Select one or more users to show answer counts."
         1  (str "Only count answers from "
                 @(subscribe [:user/display (first selected-users)]))
         (count users) "Count answers from all users."
         "Count answers from all selected users.")]]]))

(defn- StepLabelFilter [step-count]
  (let [current-filters @(subscribe [::filter-answers])]
    [StepSegment step-count "Filter By Label Answer"
     [:div
      [:div (for [{:keys [label-id name answer] :as value} current-filters]
              (let [event [::filter-answers {:value value :curset current-filters}]]
                ^{:key (str label-id "-" answer)}
                [Button {:size "mini" :primary true
                         :style {:margin "2px" :margin-left "0px"}
                         :on-click #(dispatch event)}
                 (format "%s = %s" name answer)]))]
      [:div {:style {:margin-top "0.5em"}}
       (condp = (count current-filters)
         0 [:div
            [:p {:style {:margin "0.5em 0"}}
             "Count all articles regardless of answer content."]
            [:p {:style {:margin "0.5em 0"}}
             "Click bar on chart to add filter."]]
         1 (let [{:keys [name answer]} (first current-filters)]
             [:p "Count all articles w/ 1+ " [:span.bold (str name " = " answer)]
              " answer from any user. Click button to deselect filter."])
         [:p "Include articles w/ 1+ answer for each filter."])]]]))

(defn- goto-articles-page [user-ids label-values]
  (load-settings-and-navigate
   {:filters (conj (for [{:keys [label-id value]} label-values]
                     {:has-label {:label-id label-id :values [value] :users nil
                                  :confirmed true :inclusion nil}})
                   {:has-label {:users user-ids} :confirmed true})
    :display {:show-inclusion true :show-labels false :show-notes false}
    :sort-by :content-updated
    :sort-dir :desc}))

(defn- StepGoToArticles [step-count]
  (let [answer-filters (for [{:keys [label-id raw-answer]} @(subscribe [::filter-answers])]
                         {:label-id (uuid label-id) :value raw-answer})
        users @(subscribe [::filter-users])
        on-click #(goto-articles-page users answer-filters)]
    [StepSegment step-count [:a {:href "#" :on-click (util/wrap-prevent-default on-click)}
                             "Go To Articles " [:i.arrow.right.icon]]
     [:div {:style {:margin-top "0.5em"}}
      "Open articles with label-answer and user filters."]]))

(defn- StepRescale [step-count]
  [StepSegment step-count "Select Scale"
   [:div
    [SetSubscriptionButton ::x-axis-selection "Dynamic" "Dynamic"]
    [SetSubscriptionButton ::x-axis-selection "Static" "Static"]
    [:div {:style {:margin-top "0.5em"}}
     (if (contains? @(subscribe [::x-axis-selection]) "Static")
       "Chart scale remains fixed. Useful to compare filters."
       "Chart scale adjusts to match content. Useful to compare bar size.")]]])

(defn- LabelCountControl []
  [:div.ui.segments
   [Header]
   [StepCountMethod 1]
   [StepRescale 2]
   [StepReviewType 3]
   [StepUserContentFilter 4]
   [StepLabelFilter 5]
   [StepGoToArticles 6]])

(defn- BrokenServiceView []
  [:div
   [:p "The label count analytics service is currently down. We are working to bring it back."]
   [BetaMessage]])

(defn- NoDataView []
  [:div {:id "no-data-concordance"}
   [:p "To view label data, you need to review some boolean or categorical labels."]
   [BetaMessage]])

(defn- MainView []
  [Grid {:stackable true :style {:margin "-0.5em"}}
   [Column {:width 7 :style {:padding "0.5em"}}
    [LabelCountControl]]
   [Column {:width 9 :style {:padding "0.5em"}}
    [:div.ui.segments>div.ui.segment [LabelCounts]]]])

;; TODO: Fix dispatch calls in unconditional render code
(defn- LabelCountView []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/analytics-counts project-id]] {}
      (let [data @(subscribe [:project/analytics-counts])
            all-users (set (map :user @(subscribe [::sorted-user-counts])))]
        (dispatch [::filter-users {:curset #{} :value all-users}])
        (dispatch [::filter-answers nil])
        (dispatch [::article-type-selection
                   {:curset #{} :value #{"Single" "Concordant" "Discordant"}}])
        (cond (exists? (:error data))  [BrokenServiceView]
              (empty? (:groups data))  [NoDataView]
              :else                    [MainView])))))

(def-panel :project? true :panel panel
  :uri "/analytics/labels" :params [project-id] :name analytics-labels
  :on-route (do (reload :project project-id)
                (dispatch [:set-active-panel panel]))
  :content [LabelCountView])
