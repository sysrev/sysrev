(ns sysrev.views.panels.project.analytics.concordance
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx]]
            [sysrev.data.core :refer [def-data reload]]
            [sysrev.chartjs :as chartjs]
            [sysrev.views.semantic :as S :refer [Grid Column Checkbox]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.charts :as charts]
            [sysrev.views.panels.project.analytics.common :refer [BetaMessage]]
            [sysrev.util :as util :refer [format sum round]]
            [sysrev.shared.text :as shared]
            [sysrev.macros :refer-macros [with-loader setup-panel-state def-panel]]
            [sysrev.shared.components :refer [colors]]
            [sysrev.base :as base]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :concordance])

;;; UTILITY FUNCTIONS
#_
(defn- mean [coll]
  (if (seq coll)
    (/ (sum coll) (count coll))
    0))

#_
(defn- median [coll]
  (let [sorted (vec (sort coll))
        mid-idx (quot (count sorted) 2)]
    (if (odd? (count sorted))
      (nth sorted mid-idx)
      (mean [(nth sorted (dec mid-idx))
             (nth sorted mid-idx)]))))

(defn- get-default [db key default-val]
  (as-> (get db key) x
    (if (nil? x) default-val x)))

;;; STATISTICS
(defn- measure-overall-concordance
  "Take each user-user-label key. Weight it by the count and average over concordance"
  [{:keys [label] :as _concordance-data}]
  (let [tot-weight (sum (map :count label))
        weight-con (sum (map :concordant label))]
    (/ weight-con tot-weight)))

#_
(defn- median-lbl-concordance [selected-labels selected-users concordance-data]
  (let [labels-data (->> concordance-data
                         (filter (fn [{:keys [label user-a user-b] :as _item}]
                                   (and (contains? selected-labels label)
                                        (contains? selected-users user-b)
                                        (< user-a user-b))))
                         (group-by :label))]
    (zipmap (keys labels-data)
            (for [x (vals labels-data)]
              (let [sum-count      (sum (map :count x))
                    sum-times-conc (sum (map #(* (:count %) (:concordance %)) x))
                    sum-times-disc (sum (map #(* (:count %) (- 1.0 (:concordance %))) x))]
                {:count     sum-count
                 :conc      (/ sum-times-conc sum-count)
                 :con-count (round sum-times-conc)
                 :dis-count (round sum-times-disc)})))))

#_
(defn- median-user-concordance [selected-labels selected-users concordance-data]
  (let [users-data (->> concordance-data
                        (filter (fn [{:keys [label user-a user-b] :as _item}]
                                  (and (contains? selected-labels label)
                                       (contains? selected-users user-b)
                                       (< user-a user-b))))
                        (group-by :user-a))]
    (zipmap (keys users-data)
            (for [x (vals users-data)]
              (let [sum-count       (sum (map :count x))
                    median-val      (median (map :concordance x))
                    sum-times-conc  (sum (map #(* (:count %) (:concordance %)) x))
                    sum-times-disc  (sum (map #(* (:count %) (- 1.0 (:concordance %))) x))]
                {:count sum-count
                 :conc (-> median-val (* 100) (round) (/ 100.0))
                 :con-count (round sum-times-conc)
                 :dis-count (round sum-times-disc)})))))

;;; EVENTS & SUBSCRIPTIONS
(def-data :project/concordance
  :uri     "/api/concordance"
  :loaded? (fn [db project-id _]
             (-> (get-in db [:data :project project-id])
                 (contains? :concordance)))
  :content (fn [project-id {:keys [keep-resolved] :or {keep-resolved true}}]
             {:project-id project-id :keep-resolved keep-resolved})
  :prereqs (fn [project-id _] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id _] result]
             {:db (assoc-in db [:data :project project-id :concordance] result)})
  :on-error (fn [{:keys [db error]} [project-id _] _]
              {:db (assoc-in db [:data :project project-id :concordance] {:error error})}))

(reg-sub :project/concordance
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:concordance project)))

(reg-sub ::concordance-user-selection #(::concordance-user-selection %))
(reg-event-db ::set-concordance-user-selection
              (fn [db [_ selected-user]]
                (let [current (get-default db ::concordance-user-selection #{})]
                  (assoc db ::concordance-user-selection
                         (cond (nil? selected-user)              #{}
                               (contains? current selected-user) (disj current selected-user)
                               :else                             #{selected-user})))))

(reg-sub ::concordance-label-selection #(::concordance-label-selection %))
(reg-event-db ::set-concordance-label-selection
              (fn [db [_ selected-label]]
                (let [current (get-default db ::concordance-label-selection #{})]
                  (assoc db ::concordance-label-selection
                         (cond (nil? selected-label)               #{}
                               (contains? current selected-label)  (disj current selected-label)
                               :else                               #{selected-label})))))

(reg-sub ::show-counts-step-1 #(get-default % ::show-counts-step-1 true))
(reg-sub ::show-counts-step-2 #(get-default % ::show-counts-step-2 true))
(reg-sub ::show-counts-step-3 #(get-default % ::show-counts-step-3 true))

(reg-event-db ::set-show-counts-step-1
              (fn [db [_ new-value]] (assoc db ::show-counts-step-1 new-value)))
(reg-event-db ::set-show-counts-step-2
              (fn [db [_ new-value]] (assoc db ::show-counts-step-2 new-value)))
(reg-event-db ::set-show-counts-step-3
              (fn [db [_ new-value]] (assoc db ::show-counts-step-3 new-value)))

(reg-event-fx ::keep-resolved-articles
              (fn [{:keys [db]} [_ project-id keep-resolved]]
                {:db (assoc db ::keep-resolved-articles keep-resolved)
                 :dispatch [:fetch [:project/concordance project-id
                                    {:keep-resolved (boolean keep-resolved)}]]}))

(reg-sub ::keep-resolved-articles #(get-default % ::keep-resolved-articles true))

(defn- inv-color []
  (if @(subscribe [:self/dark-theme?]) "white" "#282828"))

(defn- merge-x-scale [options & {:keys [axis-type max-val]}]
  (let [{:keys [labelString max ticks precision]}
        (case axis-type
          :concordance {:labelString "Concordance"
                        :max 1.0
                        :ticks {:color (inv-color)}}
          :count       {:labelString "Article Count"
                        :max (round (* 1.1 max-val))
                        :precision 0
                        :ticks {:precision 0
                                :stepSize (round (/ (* 1.1 max-val) 10))
                                :color (inv-color)}})
        x-scale {:x (cond-> {:position "bottom"
                             :stacked true
                             :type "linear"
                             :min 0
                             :max max
                             :ticks ticks
                             :scaleLabel (-> {:labelString labelString :display true}
                                             (merge (charts/graph-font-settings)))
                             :gridLines {:color (charts/graph-border-color)}}
                      precision (assoc :precision precision))}]
    (update options :scales #(merge % x-scale))))

(defn- label-axis [label-strings]
  (let [font (charts/graph-font-settings)]
    {:stacked true
     :scaleLabel font
     :ticks (merge font {:labels (mapv #(util/ellipsize % 35)
                                       (map str label-strings))
                         :padding 7})
     :gridLines {:drawTicks false :color (charts/graph-border-color)}}))

(defn- chart-height [num-elements]
  (+ 80 (* 2 (+ 8 (* 10 num-elements)))))

(defn- CountCheckbox [event subscription id]
  [Checkbox {:id        id
             :as        "h4"
             :style     {:margin-top "0.0rem" :margin-right "10px"}
             :checked   subscription
             :on-change #(dispatch [event (not subscription)])
             :radio     true
             :size      "large"
             :label     "Count"}])

(defn- PercCheckbox [event subscription id]
  [Checkbox {:id        id
             :as        "h4"
             :style     {:margin-top "0.0rem" :margin-right "10px"}
             :checked   (or (nil? subscription) (false? subscription))
             :on-change #(dispatch [event (not subscription)])
             :radio     true
             :label     "Percent"}])

(defn- on-click-chart [labels set-event]
  (fn [_e elts]
    (when-let [idx (and (pos-int? (.-length elts))
                        (-> elts (aget 0) .-index))]
      (when (and (nat-int? idx) (< idx (count labels)))
        (dispatch [set-event (nth labels idx)])))))

(defn- make-buttons [prefix labels values selected-labels click-set-event]
  (for [i (range (count labels))]
    (let [val (get values i)
          active? (contains? selected-labels val)]
      ^{:key (str prefix val)}
      [ui/RadioButton {:class "noselect"
                       :text (get labels i)
                       :active? active?
                       :on-click #(dispatch [click-set-event val])}])))

(defn ArticleFilters []
  (let [project-id @(subscribe [:active-project-id])]
    [:div.ui.segments
     [:div.ui.secondary.segment
      [:span [:b "Keep Resolved Articles?"]]
      [Checkbox {:id        "ignore-resolved?"
                 :as        "span"
                 :style     {:margin-top "0.0rem" :margin-left "10px"}
                 :checked   @(subscribe [::keep-resolved-articles])
                 :on-change #(dispatch [::keep-resolved-articles project-id true])
                 :radio     true
                 :label     "Keep"}]
      [Checkbox {:id        "keep-resolved?"
                 :as        "span"
                 :style     {:margin-top "0.0rem" :margin-left "10px"}
                 :checked   (not @(subscribe [::keep-resolved-articles]))
                 :on-change #(dispatch [::keep-resolved-articles project-id false])
                 :radio     true
                 :label     "Remove"}]]]))

;;; STEP 1
(defn- ConcordanceDescription []
  [:div
   [:h3 "Step 1 - Label difficulty"]
   [:p "Track label difficulty by comparing the number of articles where all 2+ users agree (concordant) vs articles where 1+ users disagree (discordant)"]
   [:p "Only boolean labels with 1+ double reviewed articles are shown."]])

(defn- LabelConcordance []
  (let [{:keys [label]} @(subscribe [:project/concordance])
        conc-data    (->> (sort-by :count > label)
                          (filter (comp pos? :count)))
        label-ids    (mapv :label-id conc-data)
        label-names  (for [{:keys [label-id]} conc-data]
                       @(subscribe [:label/display "na" (uuid label-id)]))
        concordance  (for [{:keys [concordant count]} conc-data]
                       (/ (round (* 100 (/ concordant count))) 100))
        discordance  (map #(/ (round (* 100 (- 1.0 %))) 100) concordance)
        counts       (map :count conc-data)
        con-counts   (map :concordant conc-data)
        dis-counts   (map #(- (:count %) (:concordant %)) conc-data)
        max-count    (reduce max counts)
        show-counts  @(subscribe [::show-counts-step-1])
        {:keys [blue red]} colors
        dss (->> (if show-counts
                   [{:label "concordant" :data con-counts  :backgroundColor blue}
                    {:label "discordant" :data dis-counts  :backgroundColor red}]
                   [{:label "concordant" :data concordance :backgroundColor blue}
                    {:label "discordant" :data discordance :backgroundColor red}])
                 (mapv #(merge % {:maxBarThickness 15 :stack "1"})))
        options (-> {:scales {:y (label-axis label-names)}
                     :onClick (on-click-chart label-ids ::set-concordance-label-selection)
                     :tooltips {:mode "y"}}
                    (merge-x-scale :axis-type (if show-counts :count :concordance)
                                   :max-val (when show-counts max-count))
                    (charts/wrap-default-options
                     :plugins {:legend {:display true
                                        :labels (charts/graph-font-settings
                                                 :color (inv-color))}}
                     :animate? false
                     :items-clickable? true))]
    [:div
     [:h5 "Concordant Articles by Label"]
     [:div
      [PercCheckbox ::set-show-counts-step-1 show-counts "step-1-percent-checkbox"]
      [CountCheckbox ::set-show-counts-step-1 show-counts "step-1-count-checkbox"]]
     [chartjs/horizontal-bar
      {:data {:labels label-names :datasets dss}
       :height (chart-height (count label-ids))
       :options options}]]))

;;; STEP 2
(defn- UserConcordanceDescription []
  (let [{:keys [label]} @(subscribe [:project/concordance])
        label-ids (vec (distinct (map :label-id (sort-by :count > label))))
        label-names (vec (distinct (for [label-id label-ids]
                                     @(subscribe [:label/display "na" (uuid label-id)]))))]
    [:div [:h3 "Step 2 - User Performance"]
     [:p "Discover which users have the best performance on the selected label."
      " Select a label below, or click a bar in Step 1"]
     [:div [:h5 {:style {:margin-bottom "0.5em"}}
            "Select Label:"]
      (make-buttons "step2_sel_" label-names label-ids
                    @(subscribe [::concordance-label-selection])
                    ::set-concordance-label-selection)]]))

;; todo - onClick should take you to discordant/concordant articles
(defn- UserConcordance []
  (let [selected-labels @(subscribe [::concordance-label-selection])
        conc-data     (->> (:user_label @(subscribe [:project/concordance]))
                           (sort-by :count >)
                           (filter #(contains? selected-labels (:label-id %))))
        user-ids      (mapv :user-id conc-data)
        user-names    (for [{:keys [user-id]} conc-data]
                        @(subscribe [:user/username user-id]))
        concordance   (for [{:keys [count concordant]} conc-data]
                        (-> (/ concordant count) (* 100) (round) (/ 100)))
        discordance   (map #(-> (- 1.0 %) (* 100) (round) (/ 100)) concordance)
        counts        (map :count conc-data)
        con-counts    (map :concordant conc-data)
        dis-counts    (map #(- (:count %) (:concordant %)) conc-data)
        {:keys [blue red]} colors
        con-count-ds  {:label "concordant" :backgroundColor blue :data con-counts}
        dis-count-ds  {:label "discordant" :backgroundColor red  :data dis-counts}
        con-perc-ds   {:label "concordant" :backgroundColor blue :data concordance}
        dis-perc-ds   {:label "discordant" :backgroundColor red  :data discordance}
        show-counts   @(subscribe [::show-counts-step-2])
        dss           (->> (if show-counts
                             [con-count-ds dis-count-ds]
                             [con-perc-ds dis-perc-ds])
                           (mapv #(merge % {:maxBarThickness 15 :stack "1"})))
        options       (-> {:scales {:y (label-axis user-names)}
                           :onClick (on-click-chart user-ids ::set-concordance-user-selection)}
                          (merge-x-scale :axis-type (if show-counts :count :concordance)
                                         :max-val (when show-counts
                                                    (reduce max counts)))
                          (charts/wrap-default-options
                           :plugins {:legend {:display true
                                              :labels (charts/graph-font-settings
                                                       :color (inv-color))}}
                           :items-clickable? true
                           :animate? false))]
    [:div
     [:h5 "User Concordant Articles on "
      [:span {:style {:color (:select-blue colors)}}
       @(subscribe [:label/display "na" (uuid (first selected-labels))])]]
     [:div
      [PercCheckbox ::set-show-counts-step-2 show-counts "step-2-percent-checkbox"]
      [CountCheckbox ::set-show-counts-step-2 show-counts "step-2-count-checkbox"]]
     [chartjs/horizontal-bar
      {:data {:labels user-names :datasets dss}
       :height (chart-height (count user-ids))
       :options options}]]))

;;; STEP 3
(defn- UserLabelSpecificDescription []
  (let [{:keys [label user_label]} @(subscribe [:project/concordance])
        label-ids       (vec (distinct (map :label-id (sort-by :count > label))))
        label-names     (vec (for [label-id label-ids]
                               @(subscribe [:label/display "na" (uuid label-id)])))
        selected-label  @(subscribe [::concordance-label-selection])
        user-ids        (vec (distinct (->> user_label
                                            (filter #(contains? selected-label (:label-id %)))
                                            (sort-by :count >)
                                            (map :user-id))))
        user-names      (vec (for [user-id user-ids]
                               @(subscribe [:user/username user-id])))
        lbl-buttons     (make-buttons "step_3_lbl" label-names label-ids
                                      @(subscribe [::concordance-label-selection])
                                      ::set-concordance-label-selection)
        usr-buttons     (make-buttons "step_3_usr" user-names user-ids
                                      @(subscribe [::concordance-user-selection])
                                      ::set-concordance-user-selection)]
    [:div
     [:h3 "Step 3 - User / User Investigation"]
     [:p "Select a label and a user below to discover user-user pairs are most concordant and discordant. "
      "Tracking concordance against your most trusted users can help discover difficult tasks or low performance reviewers."]
     [:div {:style {:padding-top "10px"}}
      [:h5 {:style {:margin-bottom "0.5em"}} "Select Label:"]
      lbl-buttons]
     [:div {:style {:padding-top "10px"}}
      [:h5 {:style {:margin-bottom "0.5em"}} "Select User:"]
      usr-buttons]]))

(defn- UserLabelSpecificEmpty []
  (let [selected-user   @(subscribe [::concordance-user-selection])
        selected-label  @(subscribe [::concordance-label-selection])]
    (cond (and (empty? selected-user)
               (empty? selected-label))  [:span "Select a label and then a user"]
          (empty? selected-user)         [:span "Select a user"]
          (empty? selected-label)        [:span "Select a label"])))

(defn- UserLabelSpecificConcordance []
  (let [{:keys [user_user_label]} @(subscribe [:project/concordance])
        selected-user   @(subscribe [::concordance-user-selection])
        selected-label  @(subscribe [::concordance-label-selection])
        uul-data        (->> (sort-by :count > user_user_label)
                             (filter #(and (contains? selected-label (:label-id %))
                                           (contains? selected-user (:user-a %)))))
        user-ids        (mapv :user-b uul-data)
        user-names      (for [{:keys [user-b]} uul-data]
                          @(subscribe [:user/username user-b]))
        concordance     (for [{:keys [concordant count]} uul-data]
                          (-> (/ concordant count) (* 100) (round) (/ 100)))
        discordance     (for [x concordance]
                          (-> (- 1.0 x)            (* 100) (round) (/ 100)))
        counts          (map :count uul-data)
        con-counts      (map :concordant uul-data)
        dis-counts      (for [{:keys [concordant count]} uul-data]
                          (- count concordant))
        max-count       (reduce max counts)
        {:keys [blue red select-blue]} colors
        con-count-ds    {:label "concordant" :backgroundColor blue :data con-counts}
        dis-count-ds    {:label "discordant" :backgroundColor red  :data dis-counts}
        con-perc-ds     {:label "concordant" :backgroundColor blue :data concordance}
        dis-perc-ds     {:label "discordant" :backgroundColor red  :data discordance}
        show-counts     @(subscribe [::show-counts-step-3])
        dss             (mapv #(merge % {:maxBarThickness 15 :stack "1"})
                              (if show-counts
                                [con-count-ds dis-count-ds]
                                [con-perc-ds dis-perc-ds]))
        options (-> {:scales {:y (label-axis user-names)}
                     :onClick (on-click-chart user-ids ::set-concordance-user-selection)}
                    (merge-x-scale :axis-type (if show-counts :count :concordance)
                                   :max-val (when show-counts max-count))
                    (charts/wrap-default-options
                     :plugins {:legend {:display true
                                        :labels (charts/graph-font-settings
                                                 :color inv-color)}}
                     :animate? false
                     :items-clickable? true))]
    [:div
     [:h5 "User Concordant Articles"
      " vs " [:span {:style {:color select-blue}}
              @(subscribe [:user/username (first selected-user)])]
      " on " [:span {:style {:color select-blue}}
              @(subscribe [:label/display "na" (uuid (first selected-label))])]]
     [:div
      [PercCheckbox  ::set-show-counts-step-3 show-counts "step-3-percent-checkbox"]
      [CountCheckbox ::set-show-counts-step-3 show-counts "step-3-count-checkbox"]]
     [chartjs/horizontal-bar
      {:data {:labels user-names :datasets dss}
       :height (chart-height (count user-ids))
       :options options}]]))

;;; PAGE DEFINITION
(defn- BrokenServiceView []
  [:div [:p "The concordance analytics service is currently down. We are working to bring it back."]
   [BetaMessage]])

(defn- NoDataView []
  [:div {:id "no-data-concordance"}
   [:p "To view concordance data, you need 2+ users to review boolean labels on the same article at least once."]
   [:p "Set the 'Article Review Priority' to 'balanced' or 'full' under manage -> settings to guarantee overlaps."]
   [:p "Invite a friend with the invite link on the overview page and get reviewing!"]
   [BetaMessage]])

(defn- MainView []
  (let [concordance-data  @(subscribe [:project/concordance])
        mean-conc         (* 100 (measure-overall-concordance concordance-data))
        selected-label    @(subscribe [::concordance-label-selection])
        selected-user     @(subscribe [::concordance-user-selection])
        segment           (fn [left-column right-column]
                            [Grid {:stackable true
                                   :class "segment concordance"
                                   :style {:margin-top 0}}
                             left-column
                             right-column])
        [lwidth rwidth] [7 9]]
    [:div.ui.segments.concordance
     [segment
      [Column {:width lwidth}
       [:h2 {:id "overall-concordance"} (format "Concordance %.1f%%" mean-conc)]
       (cond
         (> mean-conc 98)
         [:div.ui.success.message
          [:div.content "Great job! Your project is highly concordant."]]
         (> mean-conc 90)
         [:div.ui.warning.message
          [:div.content "Some discordance in your labels. Make sure reviewers understand tasks."]]
         :else
         [:div.ui.negative.message
          [:div.content "Significant discordance. Reviewers may not understand some tasks."]])
       [BetaMessage]
       [:p "User concordance tracks how often users agree with each other."
        (when @base/show-blog-links [:br])
        (when @base/show-blog-links
          [:span "Learn more at " [ui/UrlLink "https://blog.sysrev.com/concordance"] "."])]]
      [Column {:width rwidth :text-align "center" :vertical-align "middle"}
       [:h3 [:a {:href (shared/links :analytics)} "Youtube Demo Video"]]]]
     [segment
      [Column {:width lwidth} [:h3 "Article Filters"]
       [:p "Restrict concordance analysis to articles that match filters."]]
      [Column {:width rwidth} [ArticleFilters]]]
     [segment
      [Column {:width lwidth} [ConcordanceDescription]]
      [Column {:width rwidth} [LabelConcordance]]]
     [segment
      [Column {:width lwidth} [UserConcordanceDescription]]
      (if (empty? selected-label)
        [Column {:width rwidth
                 :text-align "center"
                 :vertical-align "middle"} "Select a label"]
        [Column {:width rwidth} [UserConcordance]])]
     [segment
      [Column {:width lwidth} [UserLabelSpecificDescription]]
      (if (or (empty? selected-label) (empty? selected-user))
        [Column {:width rwidth
                 :text-align "center"
                 :vertical-align "middle"} [UserLabelSpecificEmpty]]
        [Column {:width rwidth} [UserLabelSpecificConcordance]])]]))

(defn- OverallConcordance []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/concordance project-id {:keep-resolved true}]] {}
      (let [{:keys [label] :as cdata} @(subscribe [:project/concordance])]
        (cond (contains? cdata :error)          [BrokenServiceView]
              (zero? (sum (map :count label)))  [NoDataView]
              :else                             [MainView])))))

(def-panel :project? true :panel panel
  :uri "/analytics/concordance" :params [project-id] :name analytics-concordance
  :on-route (do (reload :project project-id)
                (dispatch [:set-active-panel panel])
                (dispatch [::set-concordance-label-selection nil])
                (dispatch [::set-concordance-user-selection nil]))
  :content [OverallConcordance])
