(ns sysrev.views.panels.project.analytics.labels
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.panels.project.description :refer [ProjectDescription]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.project.documents :refer [ProjectFilesBox]]
            [sysrev.shared.charts :refer [processed-label-color-map]]
            [sysrev.views.semantic :refer [Segment Grid Row Column Button Checkbox]]
            [sysrev.views.components.core :refer [primary-tabbed-menu secondary-tabbed-menu]]
            [sysrev.macros :refer-macros [with-loader setup-panel-state]]
            [sysrev.views.charts :as charts]
            [sysrev.views.panels.project.articles :as articles]
            [sysrev.views.components.core :as ui]
            [sysrev.charts.chartjs :as chartjs]
            [sysrev.views.panels.project.articles :refer [load-settings-and-navigate]]
            [clojure.set :refer [subset? superset?]]
            [sysrev.views.panels.project.analytics.common :refer [beta-message]]
            [sysrev.util :as util]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:project :project :analytics :labels])

(def colors {:grey "rgba(160,160,160,0.5)"
             :green "rgba(33,186,69,0.55)"
             :dim-green "rgba(33,186,69,0.35)"
             :orange "rgba(242,113,28,0.55)"
             :dim-orange "rgba(242,113,28,0.35)"
             :red "rgba(230,30,30,0.6)"
             :blue "rgba(30,100,230,0.5)"
             :purple "rgba(146,29,252,0.5)"})

; UTILITIES
(defn set-subscription-button [event subscription label dispatch-value & {:keys [color title txt-col] }]
  [Button {:size "mini" :style {:margin "2px" :margin-left "0px" :background-color (if color color nil) :color txt-col}
           :primary  (contains? @subscription dispatch-value)
           :title title
           :on-click #(dispatch [event {:value dispatch-value :curset @subscription}])} label])

(defn button-array [keyprefix labels dispatch-values set-event subscription & {:keys [colors titles txt-col] }]
  [:div {:style {:display "inline"}}
   (for [i (range (count labels))]
     (let [disp (get dispatch-values i)
           color (if colors (get colors i) nil)
           title (if colors (get titles i) nil)
           txt-col (if txt-col (get txt-col i) nil)]
       ^{:key (str keyprefix disp)} [set-subscription-button set-event subscription (get labels i) disp
                                     :color color :title title :txt-col txt-col]))])

; STATISTICS EVENTS / SUBSCRIPTIONS
(def-data :project/analytics.count-data
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

(reg-sub :project/analytics.count-data
         (fn [[_ _ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (::count-data project)))

(reg-sub ::sorted-user-counts
         (fn [_] (subscribe [:project/analytics.count-data]))
         (fn [count-data]
           (->>
             (:groups count-data)
             (map (fn [{group :group articles :articles}]
                    (distinct (map (fn [g] {:user (:user g) :articles articles}) group))))
             flatten
             (group-by :user)
             (map (fn [[k v]] {:user k :count (reduce + (map :articles v))}))
             (sort-by #(- (:count %))))))

(reg-sub
  ::filtered-count-data
  (fn [_] [(subscribe [:project/analytics.count-data])
           (subscribe [::filter-users])
           (subscribe [::filter-answers])
           (subscribe [::article-type-selection])])
  (fn [[count-data filter-users filter-lab-ans filter-conc]]
    (let [flab-ans (set (map (fn [{label :label-id answer :answer}] {:label label :answer answer}) filter-lab-ans))
          group-has-lab-ans? (fn [group] (subset? flab-ans (set (map #(select-keys % [:label :answer]) group))))
          filter-counts (filter (fn [{group :group _ :articles}]
                                  (or (empty? flab-ans) (group-has-lab-ans? group)))
                                (:groups count-data))]
      (->> (map (fn [{ulas :group articles :articles}]
                  {:group (filter (fn [ulac] (and
                                               (contains? filter-users (:user ulac))
                                               (contains? filter-conc (:concordant ulac)))) ulas)
                   :articles articles}) filter-counts)
           (filter #(> (count (:group %)) 0))))))

(reg-sub
  ::filter-label-counts
  (fn [_] (subscribe [::filtered-count-data]))
  (fn [filtered-count-data]
    "a map of [(label,answer),{:articles num :answers num}]"
    (defn group-map [group articles]
      (->>
        (map #(select-keys % [:label :answer]) group)
        (reduce (fn [lamap lblans]
                  (let [{_ :articles answers :answers} (get lamap lblans {:articles 0 :answers 0})]
                    (assoc lamap lblans {:articles articles :answers (+ articles answers)})))
                (hash-map))))
    (reduce
      (fn [lamap {group :group articles :articles}]
        (->>
          (group-map group articles)
          (reduce (fn [curmap [k {articles :articles answers :answers}]]
                    (let [{oldarts :articles oldans :answers} (get curmap k {:articles 0 :answers 0})]
                      (assoc curmap k {:articles (+ oldarts articles) :answers (+ oldans answers)})))
                  lamap)))
      (hash-map)
      filtered-count-data)))

(reg-sub
  ::user-counts
  (fn [_] (subscribe [::filtered-count-data]))
  (fn [filter-counts]
    (->> (mapcat (fn [ga] (map (fn [g] {:user (:user g) :articles (:articles ga)}) (:group ga))) filter-counts)
         (reduce (fn [aggm {user :user n :articles}] (assoc aggm user (+ (get aggm user 0) n))) (hash-map))
         (seq) (sort-by second) (reverse))))

(reg-sub
  ::overall-counts
  (fn [_] [;(subscribe [::sorted-user-counts])
           (subscribe [::user-counts])
           (subscribe [::combined-label-counts])
           (subscribe [:project/article-counts])])
  (fn [[user-counts label-counts article-counts] _]
    {:users (count user-counts)
     :labels (count (distinct (map :label-id label-counts)))
     :answers (reduce + (map :count label-counts))
     :filtered-answers (reduce + (map :answers label-counts))
     :filtered-articles (reduce + (map :articles label-counts))
     :max-filtered-answers (reduce max (map :answers label-counts))
     :max-filtered-articles (reduce max (map :articles label-counts))
     :reviewed-articles (:reviewed article-counts)
     :max-label-count (reduce max (map :count label-counts))
     :max-label-article-count (reduce max (map :articles label-counts))}))

;TODO - really shouldn't rely on a different NS subscription
(reg-sub ::combined-label-counts
         (fn [db _] [(subscribe [:sysrev.views.panels.project.overview/label-counts])
                     (subscribe [::filter-label-counts])])
         (fn [[chart-counts filtered-counts]]
           (map (fn [row]
                  (let [key {:label (str (:label-id row)) :answer (str (:value row))}]
                    (merge row (get filtered-counts key {:articles 0 :answers 0})))) chart-counts)))

;; CONTROL EVENTS / SUBSCRIPTIONS
(defn set-event [db-key exclusive]
  "Function for creating 'set-events' which add or subtract values from an app-db set"
  (fn [db [_ {value :value curset :curset}]]
    (cond
      (nil? value) (assoc db db-key (set []))
      (set? value) (assoc db db-key value)
      (contains? curset value) (assoc db db-key (disj curset value))
      exclusive (assoc db db-key (set [value]))
      :else (assoc db db-key (conj curset value)))))

(reg-event-db ::set-article-type-selection (set-event ::article-type-selection false))
(reg-sub ::article-type-selection
         (fn [db _] (if (nil? (::article-type-selection db))
                      #{"Single" "Concordant" "Discordant"}
                      (::article-type-selection db))))

(reg-event-db ::set-count-type
              (fn [db [_ {value :value _ :curset}]]
                (if (= value "Count Every Answer")
                  (assoc db ::count-type #{"Count Every Answer"})
                  (assoc db ::count-type #{"Once Per Article"}))))

(reg-sub ::count-type (fn [db _] (if (nil? (::count-type db)) #{"Count Every Answer"} (::count-type db))))

(reg-event-db ::set-filter-users
              (fn [db [_ {value :value curset :curset}]]
                (cond
                  (nil? value) (assoc db ::filter-users (set []))
                  (set? value) (assoc db ::filter-users value)
                  (contains? curset value) (assoc db ::filter-users (disj curset value))
                  :else (assoc db ::filter-users (conj curset value)))))

(reg-sub ::filter-users (fn [db _] (if (nil? (::filter-users db)) #{} (::filter-users db))))

(reg-event-db ::set-filter-answers (set-event ::filter-answers false))
(reg-sub ::filter-answers (fn [db _] (if (nil? (::filter-answers db)) #{} (::filter-answers db))))

; CHART
(reg-event-db ::set-x-axis
              (fn [db [_ {value :value curset :curset}]]
                (if (= value "Dynamic")
                  (assoc db ::x-axis-selection #{"Dynamic"})
                  (assoc db ::x-axis-selection #{"Static"}))))

(reg-sub ::x-axis-selection
         (fn [db _] (if (nil? (::x-axis-selection db)) #{"Static"} (::x-axis-selection db))))

(reg-sub
  ::x-axis
  (fn [] [(subscribe [::count-type])(subscribe [::overall-counts])(subscribe [::x-axis-selection])])
  (fn [[count-type overall-count x-axis-dynamic]]
    (let [font (charts/graph-font-settings)
          every-answer? (contains? count-type "Count Every Answer")
          max-dynamic-count (if every-answer? (:max-filtered-answers overall-count)
                                              (:max-filtered-articles overall-count))
          max-static-count (if every-answer?
                             (:max-label-count overall-count)
                             (:reviewed-articles overall-count))
          max-count (if (contains? x-axis-dynamic "Dynamic") max-dynamic-count max-static-count)]
      [{:scaleLabel (->> {:display true :labelString (if every-answer? "User Answers" "Articles")})
        :stacked false
        :ticks (->> (merge {:suggestedMin 0 :suggestedMax max-count} font))
        :gridLines {:color (charts/graph-border-color)}}])))

(defn y-axis [font]
  [{:maxBarThickness 12
    :scaleLabel font
    :ticks (->> {:padding 7} (merge font))
    :gridLines {:drawTicks false :color (charts/graph-border-color)}}])

(defn chart-onclick [entries]
  (fn [_e elts]
    (let [elts (-> elts js->clj)]
      (when (and (coll? elts) (not-empty elts))
        (when-let [idx (-> elts first (aget "_index"))]
          (let [entry (nth entries idx)
                {:keys [label-id value short-label]} (nth entries idx)
                display  @(subscribe [:label/display label-id])]
            (dispatch [::set-filter-answers
                       {:value {:label-id (str label-id) :name short-label :answer (str value) :raw-answer value}
                        :curset @(subscribe [::filter-answers])}])))))))

(defn LabelCountChart [label-ids entries count-type]
  (let [font (charts/graph-font-settings)
        entries (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids)) (:label-id %)) entries)
        max-length (if (util/mobile?) 16 22)
        labels (->> entries (mapv :value) (mapv str)
                    (mapv #(if (<= (count %) max-length) % (str (subs % 0 (- max-length 2)) "..."))))
        counts (cond
                 (contains? count-type "Count Every Answer") (->> entries (mapv :answers))
                 (contains? count-type "Once Per Article") (->> entries (mapv :articles))
                 :else (->> entries (mapv (fn [row] 0))))
        height (* 2 (+ 40
                       (* 10 (Math/round (/ (inc (count label-ids)) 3)))
                       (* 10 (count counts))))
        background-colors (->>  entries (mapv :color))
        color-map (processed-label-color-map entries)
        short-label->label-uuid (fn [short-label] @(subscribe [:label/id-from-short-label short-label]))
        legend-labels
        (->> color-map
             (sort-by #((into {} (map-indexed (fn [i e] [e i]) label-ids)) (short-label->label-uuid (:short-label %))))
             (mapv (fn [{:keys [short-label color]}] {:text short-label :fillStyle color :lineWidth 0})))
        scale @(subscribe [::scale-type])
        options (charts/wrap-default-options
                  {:scales {:xAxes @(subscribe [::x-axis]) :yAxes (y-axis font) }
                   :legend {:labels (->> {:generateLabels (fn [_] (clj->js legend-labels))} (merge font))}
                   :onClick (chart-onclick entries)}
                  :animate? false
                  :items-clickable? true)
        data {:labels labels
              :datasets [{:data (if (empty? counts) [0] counts)
                          :backgroundColor (if (empty? counts) ["#000000"] background-colors)}]}
        ]
    [:div
     [chartjs/horizontal-bar {:data data :height height :options options}]
     ]))

(defn LabelCounts []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader [[:project/label-counts project-id]] {}
                 (let [label-ids @(subscribe [:project/label-ids])
                       processed-label-counts @(subscribe [::combined-label-counts])
                       count-type @(subscribe [::count-type])]
                   [LabelCountChart label-ids processed-label-counts count-type]))))

; CONTROLS
(defn header []
  (let [sampled (:sampled @(subscribe [:project/analytics.count-data]))
        {users :users labels :labels
         tot-answers :answers tot-arts :reviewed-articles
         fil-answers :filtered-answers fil-arts :filtered-articles
         } @(subscribe [::overall-counts])]
    [:div
     [:h2 {:style {:margin-bottom "0em"}} (str "Label Counts")]
     [:h4 {:id "answer-count" :style {:margin-top "0em" :margin-bottom "0em"}} (str tot-arts " articles with " tot-answers " answers total")]
     (if sampled [:span [:b "This is a large project. A random sample was taken to keep analytics fast. "]])
     (if (> tot-answers 0)
       [:div
        [beta-message]
        [:br]
        [:div {:style {:text-align "center" :margin-top "0.5em"}} [:b (str users " selected users with " fil-answers " filtered answers")]]]
       [:span "Label count analytics helps you understand label answer distributions.
     Get started reviewing to view some charts. " [beta-message]])]))

(defn step-count-method [step-count]
  [:div {:style {:margin-top "1em"}}
   [:h5 {:style {:margin-bottom "0px"}} (str step-count ". Select Counting Method")]
   [set-subscription-button ::set-count-type (subscribe [::count-type]) "Every Answer" "Count Every Answer"
    :title "Count all answers on each article. An article included by two reviewers adds 2 to the include bar."]
   [set-subscription-button ::set-count-type (subscribe [::count-type]) "Once Per Article" "Once Per Article"
    :title "Count answers once per article. An article included by 2 reviewers adds 1 to the include bar."]

   [:span  {:style {:display "block" :margin-left "1em" :margin-top "0.5em"}}
    [:span "X-axis counts "
     (if (contains? @(subscribe [::count-type]) "Count Every Answer") "all occurrences of a given answer"
                                                                      "number of articles w/ given answer")]]])

(reg-event-db ::set-scale-type (set-event ::scale-type true))
(reg-sub ::scale-type (fn [db] (::scale-type db)))

(defn step-chart-scale [step-count]
  [:div
   [:h5 {:style {:margin-bottom "0px" :margin-top "1em"}} (str step-count ". Select Chart Scale ")]
   [set-subscription-button ::set-scale-type (subscribe [::scale-type]) "Raw Count" "Raw Count"]
   [set-subscription-button ::set-scale-type (subscribe [::scale-type]) "Raw Percent" "Raw Percent"]
   [set-subscription-button ::set-scale-type (subscribe [::scale-type]) "Label Percent" "Label Percent"]
   [:span  {:style {:display "block" :margin-left "1em" :margin-top "0.5em"}}
    (cond
      (contains? @(subscribe [::scale-type]) "Raw Count")
      [:span "Chart bars scaled to the filtered answer count."]

      (contains? @(subscribe [::scale-type]) "Raw Percent")
      [:span "Chart bars scaled to the percent of all counted answers."]

      (contains? @(subscribe [::scale-type]) "Label Percent")
      [:span "Chart bars scaled to percent of user answers with same label."]

      :else "Select a chart scale")
    ]])

(defn step-review-type [step-count]
  (let [article-type @(subscribe [::article-type-selection])]
    [:div
     [:h5 {:style {:margin-bottom "0px" :margin-top "1em"}} (str step-count ". Filter By Concordance Type")]
     [set-subscription-button ::set-article-type-selection (subscribe [::article-type-selection]) "Single" "Single"]
     [set-subscription-button ::set-article-type-selection (subscribe [::article-type-selection]) "Concordant" "Concordant"]
     [set-subscription-button ::set-article-type-selection (subscribe [::article-type-selection]) "Discordant" "Discordant"]
     [:span  {:style {:display "block" :margin-left "1em" :margin-top "0.5em"}}
      (cond
        (= 3 (count article-type)) "Count all article answers."
        (= article-type #{"Single" "Concordant"})
        "Count answers with 1+ agreeing users."
        (= article-type #{"Single" "Discordant"})
        "Count answers w/ 1 reviewer, or 2+ disagreeing reviewers."
        (= article-type #{"Concordant" "Discordant"})
        "Count answers with 2+ reviewers"
        (= article-type #{"Single"})
        "Count answers with exactly one reviewer."
        (= article-type #{"Concordant"})
        "Count answers w/ 2+ reviewers who all agree."
        (= article-type #{"Discordant"})
        "Count answers with 2+ disagreeing reviewers."
        :else "Filter answers by their article concordance.")]]))

(defn user-counts-with-zeros []
  (let [selected-users @(subscribe [::filter-users])
        all-users (mapv :user @(subscribe [::sorted-user-counts]))
        user-counts (->> (mapv (fn [[usr cnt]]
                                 {:usr usr
                                  :cnt cnt
                                  :neg-cnt (* -1 cnt)
                                  :sel-usr (if (contains? selected-users usr) 0 1)
                                  })
                               @(subscribe [::user-counts]))
                         (sort-by (juxt :sel-usr :neg-cnt)))
        missing-users (clojure.set/difference (set all-users) (set (mapv :usr user-counts)))
        user-counts-with-zeros
        (if (empty? missing-users)
          (mapv #(select-keys % [:usr :cnt]) user-counts)
          (concat
            (mapv #(select-keys % [:usr :cnt]) user-counts)
            (mapv (fn [usr] {:usr usr :cnt 0}) missing-users)))]
    user-counts-with-zeros
    ))

(defn step-user-content-filter [step-count]
  (let [selected-users @(subscribe [::filter-users])
        user-counts @(subscribe [::sorted-user-counts])
        users       (mapv :user user-counts)
        usernames   (mapv (fn [uuid] @(subscribe [:user/display uuid])) users)
        max-cnt     (reduce (fn [agg {usr :user cnt :count}] (max agg cnt)) user-counts)
        colors      (mapv (fn [{usr :user cnt :count}]
                            (if (contains? selected-users usr)
                              nil
                              (str "rgba(84, 152, 169," (max 0.2 (* 0.8 (/ cnt max-cnt))) ")")))
                          user-counts)
        titles (mapv (fn [{cnt :count}] (str cnt " answers")) user-counts)
        inv-color (if (= "Dark" (:ui-theme @(subscribe [:self/settings]))) "white" "#282828")
        txt-col (mapv (fn [{usr :user}] (if (contains? selected-users usr) "white" inv-color)) user-counts)
        ]
    [:div {:style {:margin-top "1em"}}
     [:h5 {:style {:margin-bottom "0px"}} (str step-count ". Filter By User")]
     [button-array "user-filter" usernames users
      ::set-filter-users (subscribe [::filter-users])
      :colors colors :titles titles :txt-col txt-col]
     [Button {:size "mini" :style {:margin "2px" :margin-left "0px"}
              :primary (= (count selected-users) 0)
              :on-click #(dispatch [::set-filter-users {:value #{} :curset #{}}])} "Clear Users"]
     [Button {:size "mini" :style {:margin "2px" :margin-left "0px"}
              :primary (empty? (clojure.set/difference (set users) selected-users))
              :on-click #(dispatch [::set-filter-users {:value (set users) :curset #{}}])} "All Users"]

     [:span  {:style {:display "block" :margin-left "0.5em" :margin-top "0.5em"}}
      (cond
        (empty? selected-users)
        "Count all answers w/ no user content filtering."

        (= (count selected-users) 1)
        (str "Only count answers from " @(subscribe [:user/display (first selected-users)]))

        (< (count selected-users) (count users))
        (str "Count answers from all selected users")

        :else
        (str "Count answers from all users"))]]))

(defn step-label-filter [step-count]
  (let [answer-filters @(subscribe [::filter-answers])]
    [:div {:style {:margin-top "1em"}}
     [:h5  {:style {:margin-bottom "0px"}} (str step-count ". Filter By Label Answer ")]
     [:div (for [answer-filter answer-filters]
             ^{:key (str "answer-filter-" (:label-id answer-filter) (:answer answer-filter))}
             [Button {:size "mini" :style {:margin "2px" :margin-left "0px"} :primary true
                      :on-click #(dispatch [::set-filter-answers
                                            {:value answer-filter :curset answer-filters}])}
              (str (:name answer-filter) " = " (:answer answer-filter))])]

     [:span  {:style {:display "block" :margin-left "1em" :margin-top "0.5em"}}
      (cond
        (empty? answer-filters)
        [:span "Count all articles regardless of answer content."[:br] "Click bar on chart to add filter."]

        (= 1 (count answer-filters))
        (let [answer-filter (first answer-filters)
              name (str (:name answer-filter) " = " (:answer answer-filter))]
          [:span "Count all articles w/ 1+ " [:b name] " answer from any user. Click button to deselect filter."])

        (not (empty? answer-filters))
        "Include articles w/ 1+ answer for each filter."
        )
      ]]))

(defn load-user-label-value-settings [user-ids label-values]
  (let [display {:show-inclusion true
                 :show-labels false
                 :show-notes false}
        user-filter {:has-label {:users user-ids} :confirmed true}
        filters (mapv (fn [{label-id :label-id value :value}]
                        {:has-label {:label-id label-id
                                     :users nil
                                     :values [value]
                                     :inclusion nil
                                     :confirmed true}}) label-values)]
    (load-settings-and-navigate
      {:filters (conj filters user-filter)
       :display display
       :sort-by :content-updated
       :sort-dir :desc})))

(defn step-go-to-articles [step-count]
  (let [answer-filters (mapv (fn [row] {:label-id (uuid (:label-id row)) :value (:raw-answer row)})
                             @(subscribe [::filter-answers]))
        onclick (fn [] (load-user-label-value-settings @(subscribe [::filter-users]) answer-filters))]
    [:div {:style {:margin-top "1em"}}
     [:h5  {:style {:margin "0px"}}
      (str step-count ".")
      [:a {:style {:cursor "pointer"} :on-click onclick} " Go To Articles " [:i.arrow.right.icon]]]
     [:div {:style {:margin-left "1em"}}
      [:span  "Open articles with label-answer and user filters."]]]))

(defn step-rescale [step-count]
  [:div {:style {:margin-top "1em"}}
   [:h5  {:style {:margin "0px"}} (str step-count ". Select Scale")]
   [set-subscription-button ::set-x-axis (subscribe [::x-axis-selection]) "Dynamic" "Dynamic"]
   [set-subscription-button ::set-x-axis (subscribe [::x-axis-selection]) "Static" "Static"]
   [:div {:style {:margin-left "1em" :margin-top "0.5em"}}
    (if (contains? @(subscribe [::x-axis-selection]) "Static")
      [:span "X-axis does not resize. Useful to compare filters" ]
      [:span "X-axis resizes on filter. Useful to compare bar size"])]])


(defn label-count-control []
  [:div
   [header]
   [step-count-method 1]
   [step-rescale 2]
   [step-review-type 3]
   [step-user-content-filter 4]
   [step-label-filter 5]
   [step-go-to-articles 6]
   ])

(defn broken-service-view []
  [:div
   [:span "The label count analytics service is currently down. We are working to bring it back. "]
   [beta-message]])

(defn no-data-view []
  [:div {:id "no-data-concordance"}
   "To view label data, you need to review some boolean or categorical labels."
   [:br] [beta-message]])

(defn main-view [groupcount-data]
  [Grid {:stackable true :divided "vertically"}
   [Row [Column {:width 6} [label-count-control]] [Column {:width 10} [LabelCounts]]]])

(defn label-count-view []
  (when-let [project-id @(subscribe [:active-project-id])]
    (with-loader
      [[:project/analytics.count-data project-id]] {}
      (let [label-groupcount-data @(subscribe [:project/analytics.count-data])]
        (dispatch [::set-filter-users
                   {:value (set (map #(:user %) @(subscribe [::sorted-user-counts])))
                    :curset #{}}])
        (dispatch [::set-filter-answers nil])
        (dispatch [::set-article-type-selection
                   {:value #{"Single" "Concordant" "Discordant"}
                    :curset #{}}])
        (cond
          (exists? (:error label-groupcount-data)) [broken-service-view]
          (= 0 (:answers @(subscribe [::overall-counts]))) [no-data-view]
          :else [main-view label-groupcount-data])))))


(defmethod panel-content [:project :project :analytics :labels] []
  (fn [child] [:div.ui.aligned.segment [label-count-view] child]))