(ns sysrev.views.group-label
  (:require [clojure.set :refer [rename-keys]]
            [clojure.string :as string]
            [re-frame.core :refer [subscribe reg-event-db reg-event-fx reg-sub trim-v dispatch]]
            [medley.core :as medley]
            [reagent.core :as r]
            [sysrev.util :as util]
            [sysrev.views.semantic :refer [Button Icon Dropdown Table TableHeader TableRow TableBody TableHeaderCell TableCell Input Popup]]))

(def group-label-preview-div "group-label-preview")

(defonce state (r/atom {:keypress nil
                        :current-position nil
                        :new-row-positive false
                        :max-col nil
                        :max-row nil}))

;; for setting the active group label
(reg-event-db :group-label/set-active-group-label [trim-v]
              (fn [db [label-id]]
                (assoc-in db [:state :review :labels :active-group-label]
                          label-id)))

(reg-sub :group-label/active-group-label
         (fn [db [_]]
           (get-in db [:state :review :labels :active-group-label])))

(defn increment-keys
  "Increment the key values of m starting at n"
  [m n]
  (medley/map-keys
   #(let [ith (sysrev.util/parse-integer %)]
      (if (>= ith n)
        (str (inc ith))
        (str ith)))
   m))

(reg-event-db :group-label/add-group-label-instance
              [trim-v]
              (fn [db [article-id label-id ith]]
                (let [instances (-> @(subscribe [:review/active-labels article-id "na" label-id]))
                      labels-cursor [:state :review :labels article-id label-id :labels]
                      labels (get-in db labels-cursor)
                      labels-count (-> instances :labels keys count)
                      ith-integer (sysrev.util/parse-integer ith)
                      duplicate-row (or (-> instances (get-in [:labels ith])) {})]
                  (if ith
                    (-> db
                        ;; increment all the other rows
                        (assoc-in labels-cursor (increment-keys labels (inc ith-integer)))
                        ;; add in the row
                        (assoc-in [:state :review :labels article-id label-id :labels (str (inc ith-integer))] duplicate-row))
                    (assoc-in db [:state :review :labels article-id label-id :labels (str labels-count)] {})))))

(defn reorder-keys
  [m]
  (let [ordered-keys-map (->> (keys m)
                              (map-indexed (fn [i k]
                                             {k (str i)}))
                              (apply conj))]
    (rename-keys m ordered-keys-map)))

(defn delete-label-instance
  [db [_ article-id root-label-id ith]]
  (let [labels-cursor [:state :review :labels article-id root-label-id :labels]
        article-labels-cursor [:data :articles article-id :labels @(subscribe [:self/user-id])
                               root-label-id :answer :labels]
        modified-labels (-> @(subscribe [:review/active-labels article-id "na" root-label-id])
                            :labels
                            (dissoc ith))
        reordered-modified-labels (reorder-keys modified-labels)]
    (-> db
        ;; we need to delete it here
        (assoc-in labels-cursor reordered-modified-labels)
        ;;... but also in article/labels
        (assoc-in article-labels-cursor reordered-modified-labels))))

(reg-event-fx :review/delete-group-label-instance
              (fn [{:keys [db]} [_ article-id root-label-id ith]]
                {:db (delete-label-instance db [_ article-id root-label-id ith])}))

(defn on-key-down-listener [e]
  (when (= "Tab" (.-code e))
    (.preventDefault e))
  (reset! (r/cursor state [:keypress]) (.-code e)))

(defn keypress-function
  [{:keys [arrow-left arrow-right tab]}]
  (let [keypress (r/cursor state [:keypress])]
    (condp = @keypress
      "ArrowLeft"
      (arrow-left)
      "ArrowRight"
      (arrow-right)
      "Tab"
      (tab)
      ;; default - do nothing
      nil)
    ;; reset back to nil
    (reset! keypress nil)))

(defn get-element-by-xpath [xpath]
  (-> (.evaluate js/document xpath js/document nil js/XPathResult.FIRST_ORDERED_NODE_TYPE nil)
      .-singleNodeValue))

(defn inc-col-pos
  []
  (let [current-position (r/cursor state [:current-position])
        max-col @(r/cursor state [:max-col])
        max-row @(r/cursor state [:max-row])
        {:keys [row col]} @current-position
        element-to-focus (get-element-by-xpath "//div[@id='add-group-label-instance' and not(ancestor::div[contains(@style,'display: none')])]")]
    (reset! current-position
            (cond (and (>= col max-col)
                       (= max-row row))
                  (do (when-not (nil? element-to-focus)
                        (.focus element-to-focus))
                      (if (= col max-col)
                        {:row max-row :col (+ max-col 1)}
                        {:row max-row :col col}))
                  (< col max-col)
                  {:row row
                   :col (inc col)}
                  (and (= col max-col)
                       (< row max-row))
                  {:row (inc row)
                   :col 0}
                  :else {:row row :col col}))))

(defn dec-col-pos
  []
  (let [current-position (r/cursor state [:current-position])
        {:keys [row col]} @current-position
        max-col @(r/cursor state [:max-col])]
    (reset! current-position
            (cond (> col 0)
                  {:row row
                   :col (dec col)}
                  (and (= col 0)
                       (not= row 0))
                  {:row (dec row)
                   :col max-col}
                  :else {:row row :col col}))))

(r/track! keypress-function {:arrow-left dec-col-pos
                             :arrow-right inc-col-pos
                             :tab inc-col-pos})

(defn ValueDisplay [{:keys [article-id root-label-id label-id ith]}]
  (let [answer @(subscribe [:review/sub-group-label-answer article-id root-label-id label-id ith ])
        inclusion @(subscribe [:label/answer-inclusion root-label-id label-id answer])
        value-type @(subscribe [:label/value-type root-label-id label-id])
        color (case inclusion
                true   "green"
                false  "orange"
                nil)
        values (if (= "boolean" @(subscribe [:label/value-type root-label-id label-id]))
                 (if (boolean? answer)
                   [answer] [])
                 (cond (nil? answer)        nil
                       (sequential? answer) answer
                       :else                [answer]))
        valid? (if (= value-type "string")
                 ;; we're only checking the validity of strings
                 (or @(subscribe [:label/valid-string-value? root-label-id label-id answer])
                     (clojure.string/blank? answer))
                 true)]
    [:div {:class (when color (str color "-text"))
           :style {:text-align "center"}}
     (cond
       (not valid?)
       [:div {:style {:color "red"}} "Invalid"]
       (and @(subscribe [:label/required? root-label-id label-id])
            (not @(subscribe [:label/non-empty-answer? root-label-id label-id answer])))
       "Required"
       (empty? values)
       "—"
       :else
       (string/join ", " values))]))

(defn LabelInput
  [{:keys [article-id root-label-id label-id ith]}]
  (let [value-type @(subscribe [:label/value-type root-label-id label-id])
        all-values @(subscribe [:label/all-values root-label-id label-id ith])
        answer (subscribe [:review/sub-group-label-answer article-id root-label-id label-id ith ])
        props (condp = value-type
                "boolean" {:placeholder "True or False?"
                           :search? true
                           :selection? true
                           :value @answer
                           :options [{:key true :value true :text "True"}
                                     {:key false :value false :text "False"}
                                     {:key nil :value nil :text "NA"}]
                           :multiple? false}
                "categorical" {:placeholder "Choose a label"
                               :search? true
                               :selection? true
                               :value @answer
                               :options (->> all-values
                                             (mapv #(hash-map :key % :value % :text %)))
                               :multiple? true}
                "string" {:placeholder "Enter a value"
                          :search? false
                          :selection? false
                          :value (or @answer "")})
        on-change (fn [_ data]
                    (let [value (:value (js->clj data :keywordize-keys true))]
                      (dispatch [:review/set-label-value article-id root-label-id label-id ith value])))
        {:keys [placeholder search? selection? value options multiple?]} props]
    ;; handle validity checking of string values
    (if (= value-type "string")
      (let [valid? (or @(subscribe [:label/valid-string-value? root-label-id label-id @answer])
                       (clojure.string/blank? @answer))]
        (if valid?
          (dispatch [:review/delete-invalid-label article-id root-label-id label-id ith])
          (dispatch [:review/create-invalid-label article-id root-label-id label-id ith]))
        [Input {:placeholder placeholder
                :style {:width "10em"}
                :error (not valid?)
                :autoFocus true
                :value value
                :onChange on-change}])
      ;; boolean and categorical
      [Dropdown {:placeholder placeholder
                 :style {:width "10em"}
                 :upward false
                 :searchInput {:autoFocus true}
                 :search search?
                 :selection selection?
                 :multiple multiple?
                 :closeOnBlur true
                 :value value
                 :on-change on-change
                 :options options}])))

(defn SpreadSheetAnswerCell
  [{:keys [article-id root-label-id label-id ith answers position]}]
  (let [current-position (r/cursor state [:current-position])
        answer (subscribe [:review/sub-group-label-answer article-id root-label-id label-id ith])
        id (str (gensym "spread-sheet-answer-cell-"))]
    (r/create-class
     {:reagent-render
      (fn [_]
        ;; handle missing label
        (if (and @(subscribe [:label/required? root-label-id label-id])
                 (not @(subscribe [:label/non-empty-answer? root-label-id label-id @answer])))
          (dispatch [:review/create-missing-label article-id root-label-id label-id ith])
          (dispatch [:review/delete-missing-label article-id root-label-id label-id ith]))
        [TableCell {:on-click #(reset! current-position position)
                    :id id
                    :style {:cursor "pointer"
                            :max-width "10em"
                            :min-width "7em"
                            :word-wrap "break-word"}
                    :verticalAlign "top"
                    :textAlign "center"}
         (if (= @current-position position)
           [LabelInput {:article-id article-id
                        :root-label-id root-label-id
                        :label-id label-id
                        :ith ith}]
           [ValueDisplay {:article-id article-id
                          :root-label-id root-label-id
                          :label-id label-id
                          :ith ith}])])
      :component-will-unmount (fn [_this]
                                (dispatch [:review/delete-missing-label article-id root-label-id label-id ith])
                                (dispatch [:review/delete-invalid-label article-id root-label-id label-id ith]))})))

(defn GroupLabelPopup [{:keys [category required? question examples]}]
  (let [criteria? (= category "inclusion criteria")]
    [:div.ui.inverted.grid.label-help
     {:on-click (util/wrap-user-event #(do nil))}
     [:div.middle.aligned.center.aligned.row.label-help-header
      [:div.ui.sixteen.wide.column
       [:span {:style {:font-size "110%"}}
        (str (cond (and criteria? required?)
                   "Required - Inclusion Criteria"
                   (and criteria? (not required?))
                   "Optional - Inclusion Criteria"
                   required?
                   "Required Label"
                   :else
                   "Optional Label"))]]]
     [:div.middle.aligned.row.label-help-question
      [:div.sixteen.wide.column.label-help
       [:div [:span (str question)]]
       (when (seq examples)
         [:div
          [:div.ui.small.divider]
          [:div
           [:strong "Examples: "]
           (doall (map-indexed (fn [i ex] ^{:key i}
                                 [:div.ui.small.green.label (str ex)])
                               examples))]])]]]))

(defn SpreadSheetAnswers
  [{:keys [article-id group-label-id]}]
  (r/create-class
   {:reagent-render
    (fn [{:keys [article-id group-label-id]}]
      (let [answers (subscribe [:review/active-labels article-id "na" group-label-id])
            labels (->> (vals @(subscribe [:label/labels "na" group-label-id "na"]))
                        (sort-by :project-ordering <)
                        (filter :enabled))
            label-name @(subscribe [:label/display "na" group-label-id])
            max-row (r/cursor state [:max-row])
            current-position (r/cursor state [:current-position])
            multi? (subscribe [:label/multi? "na" group-label-id])]
        [Table {:striped true
                ;;:compact true
                ;;:collapsing true
                ;;:singleLine true
                :celled true
                :style {:margin-bottom "0"
                        :border-bottom-left-radius "0"
                        :border-bottom-right-radius "0"}}
         [TableHeader {:fullWidth true}
          [TableRow {:textAlign "center"}
           [TableHeaderCell {:colSpan (+ (count labels) 1)} label-name]]]
         [TableHeader
          [TableRow {:id "sub-labels"}
           [TableHeaderCell {:style {:max-width "10em"
                                     :position "sticky"
                                     :top "0"}}]
           (doall
            (for [label labels]
              (let [root-label-id group-label-id
                    label-id (:label-id label)
                    category @(subscribe [:label/category root-label-id label-id])
                    required? @(subscribe [:label/required? root-label-id label-id])
                    question @(subscribe [:label/question root-label-id label-id])
                    examples  @(subscribe [:label/examples root-label-id label-id])]
                ^{:key (str group-label-id "-" label-id "-table-header" )}
                [TableHeaderCell {:style {:position "sticky"
                                          :top "0"
                                          :min-width "7em"
                                          :text-align "center"}}
                 [:div @(subscribe [:label/display group-label-id (:label-id label)])
                  [Popup {:trigger (r/as-element [Icon {:name "circle question"
                                                        :color "grey"
                                                        :style {:display "inline-block"
                                                                :margin-left "0.25rem"
                                                                :cursor "help"}}])
                          :content (r/as-element [GroupLabelPopup {:category category
                                                                   :required? required?
                                                                   :question question
                                                                   :examples examples}])}]]])))]]
         [TableBody
          (doall (map-indexed
                  (fn [i ith]
                    ^{:key (str group-label-id "-" ith "-row")}
                    [TableRow
                     ;; index and delete
                     [TableCell {:collapsing true
                                 :text-align "right"}
                      [Icon {:name "delete"
                             :on-click (fn [_]
                                         (let [{:keys [row]} @current-position]
                                           (dispatch [:review/delete-group-label-instance article-id group-label-id (str ith)])
                                           (swap! max-row dec)
                                           (when (> row @max-row)
                                             (reset! current-position {:row @max-row :col 0}))))
                             :style {:display "inline"
                                     :cursor "pointer"}}]
                      (when @multi?
                        [Icon {:name "circle plus"
                               :color "olive"
                               :on-click (fn [_]
                                           (let [max-row (r/cursor state [:max-row])
                                                 current-position (r/cursor state [:current-position])]
                                             (dispatch [:group-label/add-group-label-instance article-id group-label-id (str ith)])
                                             (swap! max-row inc)
                                             (reset! current-position {:row (inc ith) :col 0})))
                               :style  {:display "inline"
                                        :cursor "pointer"}}])
                      ;; row index
                      (str (inc ith))]
                     ;; actual row
                     (map-indexed
                      (fn [j label]
                        ^{:key (str group-label-id "-" ith "-row-" (:label-id label) "-cell")}
                        [SpreadSheetAnswerCell {:article-id article-id
                                                :root-label-id group-label-id
                                                :label-id (:label-id label)
                                                :ith (str ith)
                                                :position {:row i :col j}}]) labels)])
                  ;; convert all iths to integers in order to get proper sorting
                  (->> (:labels @answers) keys (map util/parse-integer) sort)))]]))
    :get-initial-state
    (fn [_]
      (let [answers (subscribe [:review/active-labels article-id "na" group-label-id])
            labels (->> (vals @(subscribe [:label/labels "na" group-label-id "na"]))
                        (sort-by :project-ordering <)
                        (filter :enabled))]
        (reset! (r/cursor state [:current-position]) {:row 0 :col 0})
        (reset! (r/cursor state [:max-col]) (- (count labels) 1))
        (reset! (r/cursor state [:max-row]) (- (count (:labels @answers)) 1)))
      nil)
    :component-did-update
    (fn [this old-argv _old-state _snapshot]
      (let [{:keys [article-id group-label-id]} (r/props this)
            ;; hack, because [:review/active-labels ...] can be nil
            row-count (max 1 (-> @(subscribe [:review/active-labels article-id "na" group-label-id]) :labels count))
            labels (->> (vals @(subscribe [:label/labels "na" group-label-id "na"]))
                        (filter :enabled))]
        (when (not= (r/props this)
                    (last old-argv))
          (reset! (r/cursor state [:current-position]) {:row 0 :col 0})
          (reset! (r/cursor state [:max-col]) (- (count labels) 1))
          (reset! (r/cursor state [:max-row]) (- row-count 1)))))
    :component-did-mount (fn [_]
                           (.removeEventListener js/document "keydown" on-key-down-listener true)
                           (.addEventListener js/document "keydown" on-key-down-listener true))
    :component-will-unmount (fn [_]
                              (.removeEventListener js/document "keydown" on-key-down-listener true))}))

(defn GroupLabelDiv
  [{:keys [article-id group-label-id]}]
  (let [multi? (subscribe [:label/multi? "na" @group-label-id])
        row-count (-> @(subscribe [:review/active-labels article-id "na" @group-label-id])
                      :labels
                      count)
        on-activate (fn [_]
                      (let [max-row (r/cursor state [:max-row])
                            current-position (r/cursor state [:current-position])]
                        (set! (.-scrollTop group-label-preview-div) (+ (.-scrollHeight group-label-preview-div)
                                                                       100))
                        (dispatch [:group-label/add-group-label-instance article-id @group-label-id])
                        (swap! max-row inc)
                        (reset! current-position {:row @max-row  :col 0})))]
    [:div {:id group-label-preview-div
           :style {:overflow-x "scroll"
                   :overflow-y "visible"
                   :min-height "8rem"
                   :margin-bottom "1rem"
                   :resize "both"
                   :overflow "auto"
                   :height "auto"}}
     [SpreadSheetAnswers {:article-id article-id
                          :group-label-id @group-label-id}]
     (when (or @multi?
               (= row-count 0))
       [:div {:style {:position "sticky"
                      :left "0"}}
        [Button {:id "add-group-label-instance"
                 :on-click on-activate
                 :onKeyPress on-activate
                 :attached "bottom"
                 :primary true}
         [Icon {:name "circle plus"}] "New Blank Row"]])]))

(defn GroupLabelEditor
  [article-id]
  (let [active-group-label (subscribe [:group-label/active-group-label])]
    (when-not (nil? @active-group-label)
      [:div {:id "group-label-editor"
             :style {:position "sticky"
                     :top "10px"
                     :z-index "1"}}
       [GroupLabelDiv {:article-id article-id
                       :group-label-id active-group-label}]])))