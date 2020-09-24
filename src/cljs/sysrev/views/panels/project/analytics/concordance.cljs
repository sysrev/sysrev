(ns sysrev.views.panels.project.analytics.concordance
  (:require
    ["jquery" :as $]
    [reagent.core :as r]
    [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx]]
    [sysrev.views.panels.project.description :refer [ProjectDescription]]
    [sysrev.nav :as nav]
    [sysrev.state.nav :refer [project-uri]]
    [sysrev.views.base :refer [panel-content]]
    [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
    [sysrev.shared.charts :refer [processed-label-color-map]]
    [sysrev.views.charts :as charts]
    [sysrev.views.components.core :refer
     [primary-tabbed-menu secondary-tabbed-menu]]
    [sysrev.views.semantic :refer [Segment Grid Row Column Checkbox Dropdown Select Button Modal]]
    [sysrev.macros :refer-macros [with-loader setup-panel-state]]
    [sysrev.charts.chartjs :as chartjs]
    [sysrev.data.core :refer [def-data]]
    [sysrev.views.components.core :refer [selection-dropdown]]
    [sysrev.util :as util]
    [goog.string :as gstring]
    [sysrev.views.panels.project.analytics.common :refer [beta-message]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :concordance])

; UTILITY FUNCTIONS
(defn mean [coll]
  (let [sum (apply + coll)
        count (count coll)]
    (if (pos? count)
      (/ sum count)
      0)))

(defn median [coll]
  (let [sorted (sort coll)
        cnt (count sorted)
        halfway (quot cnt 2)]
    (if (odd? cnt)
      (nth sorted halfway) ; (1)
      (let [bottom (dec halfway)
            bottom-val (nth sorted bottom)
            top-val (nth sorted halfway)]
        (mean [bottom-val top-val])))))

(def example-concordance
  [
   {:user-a "joe" :user-b "tom"   :label "Include" :concordance 0.1 :count 100}
   {:user-a "joe" :user-b "james" :label "Include" :concordance 0.4 :count 100}
   {:user-a "joe" :user-b "joe"   :label "Include" :concordance 1.0 :count 100}

   {:user-a "joe" :user-b "tom"   :label "Species" :concordance 0.1 :count 80}
   {:user-a "joe" :user-b "james" :label "Species" :concordance 0.5 :count 80}
   {:user-a "joe" :user-b "joe"   :label "Species" :concordance 1.0 :count 80}

   {:user-a "james" :user-b "tom" :label "Include"   :concordance 0.1 :count 100}
   {:user-a "james" :user-b "james" :label "Include" :concordance 1.0 :count 100}
   {:user-a "james" :user-b "joe" :label "Include"   :concordance 0.1 :count 100}

   {:user-a "james" :user-b "tom" :label "Species"   :concordance 0.1 :count 80 }
   {:user-a "james" :user-b "james" :label "Species" :concordance 1.0 :count 80 }
   {:user-a "james" :user-b "joe" :label "Species"   :concordance 1.0 :count 80 }

   {:user-a "tom" :user-b "tom" :label "Include"   :concordance 1.0 :count 100}
   {:user-a "tom" :user-b "james" :label "Include" :concordance 0.1 :count 80 }
   {:user-a "tom" :user-b "joe" :label "Include"   :concordance 0.1 :count 100}

   {:user-a "tom" :user-b "tom" :label "Species"   :concordance 1.0     :count 80 }
   {:user-a "tom" :user-b "james" :label "Species" :concordance 0.1 :count 100}
   {:user-a "tom" :user-b "joe" :label "Species"   :concordance 0.1   :count 80 }
   ])

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :bright-green "rgba(33,186,69,0.9)"
             :gold "rgba(255,215,0,1.0)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :bright-orange "rgba(242,113,28,2.0)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(255, 86, 77,1.0)"
             :blue "rgba(84, 152, 169,1.0)"
             :purple "rgba(146,29,252,0.5)"
             :bright-purple "rgba(146,29,252,1.0)"
             :select-blue "rgb(50,150,226)"})

(defn all-users [concordance-data] (set (map :user-a concordance-data)))

(defn all-labels [concordance-data] (set (map :label concordance-data)))

; STATISTICS
(defn measure-overall-concordance [concordance-data]
  "Take each user-user-label key. Weight it by the count and average over concordance"
  (let [conc-data  (:label concordance-data)
        tot-weight (reduce + (mapv #(:count %) conc-data))
        weight-con (reduce + (mapv #(:concordant %) conc-data))]
    (/ weight-con tot-weight)))

(defn median-lbl-concordance [selected-labels selected-users concordance-data]
  (let [con-data  (filter (fn [item]
                            (and
                              (contains? selected-labels (:label item))
                              (contains? selected-users (:user-b item))
                              (< (:user-a item) (:user-b item)))) concordance-data)
        con-gp    (group-by :label con-data)
        lbls      (keys con-gp)
        con-val   (mapv (fn [x]
                          {
                           :conc      (/ (reduce + (mapv (fn [uul] (* (:count uul) (:concordance uul))) x)) (reduce + (mapv :count x)))
                           :count     (reduce + (mapv :count x))
                           :con-count (js/Math.round (reduce + (mapv (fn [uul] (* (:count uul) (:concordance uul))) x)))
                           :dis-count (js/Math.round (reduce + (mapv (fn [uul] (* (:count uul) (- 1.0 (:concordance uul)))) x)))
                           }) (vals con-gp))]
    (zipmap lbls con-val)))

(defn median-user-concordance [selected-labels selected-users concordance-data]
  (let [con-data (filter (fn [item]
                           (and
                             (contains? selected-labels (:label item))
                             (contains? selected-users (:user-b item))
                             (< (:user-a item) (:user-b item))
                             ))
                         concordance-data)
        con-gp   (group-by :user-a con-data)
        users    (keys con-gp)
        con-val  (mapv (fn [x]
                         {
                          :conc (/ (js/Math.round (* 100 (median (mapv :concordance x)) )) 100.0)
                          :count (reduce + (mapv :count x))
                          :con-count (js/Math.round (reduce + (mapv (fn [uul] (* (:count uul) (:concordance uul))) x)))
                          :dis-count (js/Math.round (reduce + (mapv (fn [uul] (* (:count uul) (- 1.0 (:concordance uul)))) x)))
                          }) (vals con-gp))]
    (zipmap users con-val)))

; EVENTS & SUBSCRIPTIONS
(def-data
  :project/concordance
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id])
                 (contains? :concordance)))
  :uri (fn [_] "/api/concordance")
  :content (fn [project-id & {:keys [keep-resolved] :or {keep-resolved true}}]
             {:project-id project-id :keep-resolved keep-resolved})
  :prereqs (fn [project-id] [[:project project-id]])
  :process (fn [{:keys [db]} [project-id] result]
             (util/log "processing...")
             (util/log (str result))
             {:db (assoc-in db [:data :project project-id :concordance] result)})
  :on-error (fn [{:keys [db error]} [project-id] _]
              {:db (assoc-in db [:data :project project-id :concordance] {:error error})}))

(reg-sub :project/concordance
         (fn [[_ _ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:concordance project)))

(reg-event-db
  ::set-concordance-user-selection
  (fn [db [_ selected-user]]
    (let [curlist (if (nil? (::concordance-user-selection db)) (set []) (::concordance-user-selection db))]
      (cond
        (nil? selected-user)              (assoc db ::concordance-user-selection (set []))
        (contains? curlist selected-user) (assoc db ::concordance-user-selection (disj curlist selected-user))
        :else                             (assoc db ::concordance-user-selection (set [selected-user]))))))

(reg-sub ::concordance-user-selection (fn [db _] (::concordance-user-selection db)))

(reg-event-db
  ::set-concordance-label-selection
  (fn [db [_ selected-label]]
    (let [curlist (if (nil? (::concordance-label-selection db)) (set []) (::concordance-label-selection db))]
      (cond
        (nil? selected-label)               (assoc db ::concordance-label-selection (set []))
        (contains? curlist selected-label)  (assoc db ::concordance-label-selection (disj curlist selected-label))
        :else                               (assoc db ::concordance-label-selection (set [selected-label]))))))

(reg-sub ::concordance-label-selection (fn [db _] (::concordance-label-selection db)))

(reg-event-db ::set-show-counts-step-1 (fn [db [_ new-value]] (assoc db ::show-counts-step-1 new-value)))

(reg-sub ::show-counts-step-1 (fn [db _] (if (nil? (::show-counts-step-1 db)) true (::show-counts-step-1 db))))

(reg-event-db ::set-show-counts-step-2 (fn [db [_ new-value]] (assoc db ::show-counts-step-2 new-value)))

(reg-sub ::show-counts-step-2 (fn [db _] (if (nil? (::show-counts-step-2 db)) true (::show-counts-step-2 db))))

(reg-event-db ::set-show-counts-step-3 (fn [db [_ new-value]] (assoc db ::show-counts-step-3 new-value)))

(reg-sub ::show-counts-step-3 (fn [db _] (if (nil? (::show-counts-step-3 db)) true (::show-counts-step-3 db))))

(reg-event-fx
  ::set-keep-resolved-articles
  (fn [cofx [_ new-value]]
    (util/log (str "newvalue-" new-value))
    {:db (assoc (:db cofx) ::keep-resolved-articles (:value new-value))
     :dispatch [:reload [:project/concordance (:project-id new-value) :keep-resolved (:value new-value)]]}))

(reg-sub ::keep-resolved-articles (fn [db _] (if (nil? (::keep-resolved-articles db)) true (::keep-resolved-articles db))))

; CHART SETTINGS
(defn inv-color [] (if (= "Dark" (:ui-theme @(subscribe [:self/settings]))) "white" "#282828"))

(defn conc-axis []
    {:id "conc"
     :type "linear"
     :position "bottom"
     :scaleLabel (->> {:display true :labelString "Concordance"} (merge (charts/graph-font-settings)))
     :stacked true
     :ticks {:min 0 :max 1.0 :fontColor (inv-color)}
     :gridLines {:color (charts/graph-border-color)}})

(defn count-axis [max]
    {:id "count"
     :type "linear"
     :position "bottom"
     :scaleLabel (->> {:display true :labelString "Article Count"} (merge (charts/graph-font-settings)))
     :stacked true
     :ticks {:min 0 :max (js/Math.round (* 1.1 max))
             :precision 0 :stepSize (/ (* 1.1 max) 10)
             :fontColor (inv-color)}
     :precision 0
     :gridLines {:color (charts/graph-border-color)}})

(defn label-axis []
  (let [font (charts/graph-font-settings)]
  [{:id "label"
    :maxBarThickness 15
    :stacked true
    :scaleLabel font
    :ticks (->> {
                 :padding 7
                 :callback (fn [text]
                             (cond
                               (> (.-length text) 35) (str (.substr text 0 32) "...")
                               :else text))}
                (merge font))
    :gridLines {:drawTicks false :color (charts/graph-border-color)}}]))

(defn height [num-elements] (+ 80 (* 2 (+ 8 (* 10 num-elements)))))

(defn count-checkbox [event subscription id]
  [Checkbox {:id        id
             :as        "h4"
             :style     {:margin-top "0.0rem" :margin-right "10px" }
             :checked   subscription
             :on-change #(dispatch [event (not subscription)])
             :radio     true
             :size      "large"
             :label     "Count"}])

(defn perc-checkbox [event subscription id]
  [Checkbox {:id        id
             :as        "h4"
             :style     {:margin-top "0.0rem" :margin-right "10px" }
             :checked   (or (nil? subscription) (false? subscription))
             :on-change #(dispatch [event (not subscription)])
             :radio     true
             :label     "Percent"}])

(defn chart-onlick [labels set-event]
  (fn [_e elts]
    (let [elts (-> elts js->clj)]
      (when (and (coll? elts) (not-empty elts))
        (when-let [idx (-> elts first (aget "_index"))]
          (dispatch [set-event (nth labels idx)]))))))

(defn make-buttons [prefix labels disp-value selected-labels click-set-event]
  (map (fn [i]
         (let [lbl (get labels i)
               disp-value (get disp-value i)]
             ^{:key (str prefix disp-value)}
             [Button {:size "mini"
                      :primary (contains? selected-labels disp-value)
                      :secondary (not (contains? selected-labels disp-value))
                      :style {:margin "2px"}
                      :on-click #(dispatch [click-set-event disp-value])} lbl]))
       (range (count labels))))

; ARTICLE FILTERS
(defn article-filters [project-id]
  [:div.ui.segments
   [:div.ui.segment
    [:span [:b "Keep Resolved Articles?"]]
    [Checkbox {:id        "ignore-resolved?"
               :as        "span"
               :style     {:margin-top "0.0rem" :margin-left "10px" }
               :checked   @(subscribe [::keep-resolved-articles])
               :on-change #(dispatch [::set-keep-resolved-articles {:project-id project-id :value true}])
               :radio     true
               :label     "Keep"}]
    [Checkbox {:id        "keep-resolved?"
               :as        "span"
               :style     {:margin-top "0.0rem" :margin-left "10px" }
               :checked   (not @(subscribe [::keep-resolved-articles]))
               :on-change #(dispatch [::set-keep-resolved-articles {:project-id project-id :value false}])
               :radio     true
               :label     "Remove"}]]])

; STEP 1
(defn concordance-description []
  [:div
   [:h3 "Step 1 - Label difficulty"]
   [:span "Track label difficulty by comparing the number of articles where all 2+ users agree (concordant) vs articles where 1+ users disagree (discordant)"]
   [:br][:br] [:span "Only boolean labels with 1+ double reviewed articles are shown."]])

(defn label-concordance [concordance-data]
  (let [conc-data       (filter #(> (:count %) 0) (sort-by :count > (:label concordance-data)))
        labels          (mapv (fn [row] @(subscribe [:label/display "na" (uuid (:label-id row))])) conc-data)
        concordance     (mapv (fn [r] (/ (js/Math.round (* 100 (/ (:concordant r) (:count r)))) 100)) conc-data)
        discordance     (mapv #(/ (js/Math.round (* 100 (- 1.0 %))) 100) concordance)
        counts          (mapv :count conc-data)
        con-counts      (mapv :concordant conc-data)
        dis-counts      (mapv (fn [r] (- (:count r) (:concordant r))) conc-data)
        max-count       (reduce max counts)
        height          (height (count labels))
        show-counts     @(subscribe [::show-counts-step-1])
        con-count-ds {:xAxisID "count" :label "concordant" :backgroundColor (:blue colors) :data con-counts  :stack "1"}
        dis-count-ds {:xAxisID "count" :label "discordant" :backgroundColor (:red colors)  :data dis-counts  :stack "1"}
        con-perc-ds  {:xAxisID "conc"  :label "concordant" :backgroundColor (:blue colors) :data concordance :stack "1"}
        dis-perc-ds  {:xAxisID "conc"  :label "discordant" :backgroundColor (:red colors)  :data discordance :stack "1"}
        data {:labels labels :datasets (if show-counts [con-count-ds dis-count-ds] [con-perc-ds dis-perc-ds])}
        options (charts/wrap-default-options
                  {:legend  {:display true :labels {:fontColor (inv-color)}}
                   :scales  {:xAxes (if show-counts [(count-axis max-count)] [(conc-axis)]) :yAxes (label-axis)}
                   :onClick (chart-onlick labels ::set-concordance-label-selection)}
                  :animate? true :items-clickable? true)]
    [:div
     [:h5 {:style {:display "inline-block"}} "Concordant Articles by Label"]
     [:div
      [perc-checkbox ::set-show-counts-step-1 show-counts "step-1-percent-checkbox"]
      [count-checkbox ::set-show-counts-step-1 show-counts "step-1-count-checkbox"]]
     [chartjs/horizontal-bar {:data data :height height :options options}]]))

; STEP 2
(defn user-concordance-description [concordance-data]
  (let [labels (vec (distinct (mapv :label-id (sort-by :count > (:label concordance-data)))))
        label-names (vec (distinct (mapv (fn [lid] @(subscribe [:label/display "na" (uuid lid)])) labels)))
        selected-label @(subscribe [::concordance-label-selection])
        buttons (make-buttons "step2_sel_" label-names labels selected-label ::set-concordance-label-selection)]
    [:div
     [:h3 "Step 2 - User Performance"]
     [:span "Discover which users have the best performance on the selected label. "]
     [:span "Select a label below, or click a bar in Step 1"]
     [:div {:style {:padding-top "10px"}}
      [:h5 "Select Label:"] buttons ]]))

(defn user-concordance-empty [concordance-data]  [:div [:span "Select a label"]])

;todo - onClick should take you to discordant/concordant articles
(defn user-concordance [concordance-data]
  (let [selected-labels @(subscribe [::concordance-label-selection])
        conc-data       (filter #(contains? selected-labels (:label-id %)) (sort-by :count > (:user_label concordance-data)))
        users           (mapv (fn [{user :user-id}] @(subscribe [:user/display user])) conc-data)
        concordance     (mapv (fn [r] (/ (js/Math.round (* 100 (/ (:concordant r) (:count r)))) 100)) conc-data)
        discordance     (mapv #(/ (js/Math.round (* 100 (- 1.0 %))) 100) concordance)
        counts          (mapv :count conc-data)
        con-counts      (mapv :concordant conc-data)
        dis-counts      (mapv (fn [r] (- (:count r) (:concordant r))) conc-data)
        height          (height (count users))
        con-count-ds    {:xAxisID "count" :label "concordant" :backgroundColor (:blue colors) :data con-counts  :stack "1" }
        dis-count-ds    {:xAxisID "count" :label "discordant" :backgroundColor (:red colors)  :data dis-counts  :stack "1" }
        con-perc-ds     {:xAxisID "conc"  :label "concordant" :backgroundColor (:blue colors) :data concordance :stack "1"}
        dis-perc-ds     {:xAxisID "conc"  :label "discordant" :backgroundColor (:red colors)  :data discordance :stack "1" }
        show-counts   @(subscribe [::show-counts-step-2])
        data {:labels users :datasets (if show-counts [con-count-ds dis-count-ds] [con-perc-ds dis-perc-ds]) }
        options (charts/wrap-default-options
                  {:legend {:display true :labels {:fontColor (inv-color)}}
                   :scales {:xAxes (if show-counts [(count-axis (reduce max counts))][(conc-axis)]) :yAxes (label-axis)}
                   :onClick (chart-onlick users ::set-concordance-user-selection)}
                  :items-clickable? true
                  :animate? true)]
    [:div
     [:h5 {:style {:display "inline-block"}} "User Concordant Articles on  "
      [:span {:style {:color (:select-blue colors)}} @(subscribe [:label/display "na" (uuid (first selected-labels))])]]
     [:div
      [perc-checkbox ::set-show-counts-step-2 show-counts "step-2-percent-checkbox"]
      [count-checkbox ::set-show-counts-step-2 show-counts "step-2-count-checkbox"]]
     [chartjs/horizontal-bar {:data data :height height :options options}]]))

;; STEP 3
(defn user-label-specific-description [concordance-data]
  (let [labels (vec (distinct (mapv :label-id (sort-by :count > (:label concordance-data)))))
        label-names (mapv (fn [lid] @(subscribe [:label/display "na" (uuid lid)])) labels)
        selected-label  @(subscribe [::concordance-label-selection])
        users  (vec (distinct (mapv :user-id
                                    (sort-by :count >
                                             (filter #(contains? selected-label (:label-id %))
                                                     (:user_label concordance-data))))))
        user-names  (mapv (fn [uid] @(subscribe [:user/display uid])) users)
        lbl-buttons (make-buttons "step_3_lbl" label-names labels @(subscribe [::concordance-label-selection]) ::set-concordance-label-selection)
        usr-buttons (make-buttons "step_3_usr" user-names users  @(subscribe [::concordance-user-selection])  ::set-concordance-user-selection)]
    [:div
     [:h3 "Step 3 - User / User Investigation"]
     [:span "Select a label and a user below to discover user-user pairs are most concordant and discordant. "]
     [:span "Tracking concordance against your most trusted users can help discover difficult tasks or low performance reviewers."]
     [:div {:style {:padding-top "10px"}} [:h5 "Select Label:"] lbl-buttons ]
     [:div {:style {:padding-top "10px"}} [:h5 "Select User :"] usr-buttons ]]))

(defn user-label-specific-empty [concordance-data]
  (let [selected-user   @(subscribe [::concordance-user-selection])
        selected-label  @(subscribe [::concordance-label-selection])]
    (cond
      (and (empty? selected-user) (empty? selected-label)) [:span "Select a label and then a user"]
      (empty? selected-user)                               [:span "Select a user"]
      (empty? selected-label)                              [:span "Select a label"])))

(defn user-label-specific-concordance [concordance-data]
  (let [selected-user   @(subscribe [::concordance-user-selection])
        selected-label  @(subscribe [::concordance-label-selection])
        conc-data       (filter (fn [r] (and
                                          (contains? selected-label (:label-id r))
                                          (contains? selected-user (:user-a r))))
                                (sort-by :count > (:user_user_label concordance-data)))

        users           (mapv (fn [row] @(subscribe [:user/display (:user-b row)])) conc-data)
        concordance     (mapv (fn [r] (/ (js/Math.round (* 100 (/ (:concordant r) (:count r)))) 100)) conc-data)
        discordance     (mapv #(/ (js/Math.round (* 100 (- 1.0 %))) 100) concordance)
        counts          (mapv :count conc-data)
        con-counts      (mapv :concordant conc-data)
        dis-counts      (mapv (fn [r] (- (:count r) (:concordant r))) conc-data)
        max-count       (reduce max counts)
        height          (height (count users))

        con-count-ds {:xAxisID "count" :label "concordant" :backgroundColor (:blue colors) :data con-counts  :stack "1"}
        dis-count-ds {:xAxisID "count" :label "discordant" :backgroundColor (:red colors)  :data dis-counts  :stack "1"}
        con-perc-ds  {:xAxisID "conc" :label "concordant"  :backgroundColor (:blue colors) :data concordance :stack "1"}
        dis-perc-ds  {:xAxisID "conc" :label "discordant" :backgroundColor (:red colors)   :data discordance :stack "1"}
        show-counts   @(subscribe [::show-counts-step-3])
        data          {:labels users :datasets (if show-counts [con-count-ds dis-count-ds] [con-perc-ds dis-perc-ds]) }
        options       (charts/wrap-default-options
                        {:legend {:display true :labels {:fontColor (inv-color)}}
                         :scales {:xAxes (if show-counts [(count-axis max-count)] [(conc-axis)]) :yAxes (label-axis) }
                         :onClick (chart-onlick users ::set-concordance-user-selection)}
                        :animate? true
                        :items-clickable? true)]
    [:div
     [:h5 {:style {:display "inline-block"}} "User Concordant Articles "
      " vs " [:span {:style {:color (:select-blue colors)}} @(subscribe [:user/display (first selected-user)])]
      " on " [:span {:style {:color (:select-blue colors)}}
              @(subscribe [:label/display "na" (uuid (first selected-label))])]]
     [:div
      [perc-checkbox  ::set-show-counts-step-3 show-counts "step-3-percent-checkbox"]
      [count-checkbox ::set-show-counts-step-3 show-counts "step-3-count-checkbox"]]
     [chartjs/horizontal-bar {:data data :height height :options options}]]))

; PAGE DEFINITION
(defn broken-service-view []
  [:div [:span "The concordance analytics service is currently down. We are working to bring it back."]
   [:br][beta-message]])

(defn no-data-view []
  [:div {:id "no-data-concordance"}
   "To view concordance data, you need 2+ users to review boolean labels on the same article at least once."
   [:br][:br] "Set the 'Article Review Priority' to 'balanced' or 'full' under manage -> settings to guarantee overlaps."
   [:br][:br] "Invite a friend with the invite link on the overview page and get reviewing!"
   [:br][beta-message]])

(defn main-view [project-id concordance-data]
  (let [mean-conc         (* 100 (measure-overall-concordance concordance-data))
        selected-label    @(subscribe [::concordance-label-selection])
        selected-user     @(subscribe [::concordance-user-selection])]
    [Grid {:stackable true :divided "vertically"}
     [Row
      [Column {:width 8}
       [:h2 {:id "overall-concordance"} (gstring/format "Concordance %.1f%%" mean-conc)]
       (cond
         (> mean-conc 98) [:span {:style {:color (:bright-green colors)}} "Great job! Your project is highly concordant"]
         (> mean-conc 90) [:span {:style {:color (:bright-orange colors)}} "Some discordance in your labels. Make sure reviewers understand tasks "]
         :else            [:span {:style {:color (:red colors)}} "Significant discordance. Reviewers may not understand some tasks. "])
       [:br] [beta-message]
       [:br] [:span "User concordance tracks how often users agree with each other.
       Learn more at " [:a {:href "https://blog.sysrev.com/analytics"} "blog.sysrev.com/analytics."]]]
      [Column {:width 8 :text-align "center" :vertical-align "middle"}
       [:h3 [:a {:href "https://www.youtube.com/watch?v=HmQhiVNtB2s"} "Youtube Demo Video"]]]]
     [Row
      [Column {:width 6}  [:h3 "Article Filters"][:span "Restrict concordance analysis to articles that match filters."]]
      [Column {:width 10} [article-filters project-id]]]

     [Row
      [Column {:width 6}  [concordance-description]]
      [Column {:width 10} [label-concordance concordance-data]]]
     [Row
      [Column {:width 6}  [user-concordance-description concordance-data]]
      (if (empty? selected-label)
        [Column {:width 10 :text-align "center" :vertical-align "middle"} [user-concordance-empty concordance-data]]
        [Column {:width 10} [user-concordance concordance-data]])]
     [Row
      [Column {:width 6}  [user-label-specific-description concordance-data]]
      (if (or (empty? selected-label) (empty? selected-user) )
        [Column {:width 10 :text-align "center" :vertical-align "middle"} [user-label-specific-empty concordance-data]]
        [Column {:width 10} [user-label-specific-concordance concordance-data]])]]))

(defn overall-concordance []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader
      [[:project/concordance project-id :keep-resolved true]] {}
      (let [concordance-data @(subscribe [:project/concordance])]
        (cond
          (exists? (:error concordance-data)) [broken-service-view]
          (->> (:label concordance-data) (mapv :count) (reduce +) (= 0)) [no-data-view]
          :else [main-view project-id concordance-data])))))

(defn ConcordanceView []
  (r/create-class
    {:reagent-render (fn [] [overall-concordance])
     :component-did-mount (fn []
                            (dispatch [::set-concordance-label-selection nil])
                            (dispatch [::set-concordance-user-selection nil]))}))

(defmethod panel-content [:project :project :analytics :concordance] []
  (fn [child]
    [:div.ui.aligned.segment
     [ConcordanceView] child]))