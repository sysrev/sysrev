(ns sysrev.views.article-list.filters
  (:require ["jquery" :as $]
            [clojure.string :as str]
            [clojure.set :as set]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.action.core :as action]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.state.ui :as ui-state]
            [sysrev.state.label :refer [project-overall-label-id]]
            [sysrev.views.components.core :as ui :refer [UiHelpIcon]]
            [sysrev.views.article-list.base :as al]
            [sysrev.views.semantic :as S]
            [sysrev.shared.labels :refer [predictable-label-types]]
            [sysrev.util :as util :refer
             [in? map-values css space-join wrap-parens parse-integer parse-number
              when-test to-uuid]]))

(reg-sub ::inputs
         (fn [[_ context path]]
           [(subscribe [::al/get context (concat [:inputs] path)])])
         (fn [[input-val]] input-val))

(defn create-filter [db filter-type]
  {filter-type (merge (case filter-type
                        :has-user    {:user nil, :content nil, :confirmed nil}
                        :source      {:source-ids nil}
                        :has-label   {:label-id nil, :users nil, :values nil, :inclusion nil, :confirmed nil}
                        :consensus   {:status nil, :inclusion nil}
                        :prediction  {:label-id (project-overall-label-id db),
                                      :label-value true, :direction :above, :score 50})
                      {:editing? true})})

(defn- filter-presets-impl [self-id]
  {:self       {:filters [{:has-user {:user self-id, :content nil, :confirmed nil}}]
                :display {:self-only true, :show-inclusion false, :show-labels true
                          :show-notes true, :show-unconfirmed true}}
   :content    {:filters [{:has-user {:user nil, :content nil, :confirmed nil}}]
                :display {:self-only false, :show-inclusion false
                          :show-labels true, :show-notes true}}
   :inclusion  {:filters [{:consensus {:status nil, :inclusion nil}}]
                :display {:self-only false, :show-inclusion true
                          :show-labels false, :show-notes false}}})

(reg-sub :articles/filter-presets
         :<- [:self/user-id]
         (fn [self-id _]
           (filter-presets-impl self-id)))

(defn filter-values-equal?
  "Tests if two filter values are equivalent, treating missing and null
  field values as identical."
  [f1 f2]
  (and (= (keys f1) (keys f2))
       (->> (set/union (-> f1 vals first keys)
                       (-> f2 vals first keys))
            (every? #(= (-> f1 vals first (get %))
                        (-> f2 vals first (get %)))))))

(defn filter-sets-equal?
  "Tests if two sets of filter values are equivalent, treating missing
  and null field values as identical."
  [fs1 fs2]
  (and (= (count fs1) (count fs2))
       (->> fs1 (every? (fn [f] (some #(filter-values-equal? % f) fs2))))
       (->> fs2 (every? (fn [f] (some #(filter-values-equal? % f) fs1))))))

(defn- reset-filters-input [db context]
  (al/set-state db context [:inputs :filters] nil))

(reg-event-db ::reset-filters-input [trim-v]
              (fn [db [context]] (reset-filters-input db context)))

(defn- get-filters-input [db context]
  (let [input (al/get-state db context [:inputs :filters])
        active (al/get-active-filters db context)]
    (if (nil? input) active input)))

(reg-sub ::filters-input
         (fn [[_ context]]
           [(subscribe [::inputs context [:filters]])
            (subscribe [::al/filters context])])
         (fn [[input active]]
           (if (nil? input) active input)))

(reg-event-db ::update-filter-input [trim-v]
              (fn [db [context filter-idx update-fn]]
                (let [ifilter (al/get-state db context [:inputs :filters filter-idx])
                      filter-type (first (keys ifilter))
                      value (first (vals ifilter))]
                  (al/set-state db context [:inputs :filters filter-idx]
                                {filter-type (update-fn value)}))))

(defn- remove-null-filters [db context]
  (let [ifilters (al/get-state db context [:inputs :filters])
        filters (al/get-state db context [:filters])
        fix #(->> % (remove nil?) vec)]
    (cond-> db
      (not-empty filters)   (al/set-state context [:filters] (fix filters))
      (not-empty ifilters)  (al/set-state context [:inputs :filters] (fix ifilters)))))

(defn- delete-filter [db context filter-idx]
  (let [ifilters (al/get-state db context [:inputs :filters])
        filters (al/get-state db context [:filters])]
    (cond-> db
      (contains? filters filter-idx)   (al/set-state context [:filters filter-idx] nil)
      (contains? ifilters filter-idx)  (al/set-state context [:inputs :filters filter-idx] nil)
      true                             (remove-null-filters context))))

(reg-event-db ::delete-filter [trim-v]
              (fn [db [context filter-idx]]
                (delete-filter db context filter-idx)))

(defn- edit-filter [db context filter-idx]
  (let [filters (al/get-state db context [:filters])
        have-filter? (and (vector? filters) (contains? filters filter-idx))]
    (if-not have-filter? db
            (al/set-state db context [:inputs :filters]
                          (vec (map-indexed (fn [i entry]
                                              (let [[[fkey fval]] (vec entry)]
                                                (if (= i filter-idx)
                                                  {fkey (assoc fval :editing? true)}
                                                  {fkey (dissoc fval :editing?)})))
                                            filters))))))

(reg-event-db ::edit-filter [trim-v]
              (fn [db [context filter-idx]]
                (edit-filter db context filter-idx)))

(defn- process-filter-input [ifilter]
  (let [[[filter-type value]] (vec ifilter)]
    (->> (case filter-type
           :has-user    (let [{:keys [user]} value]
                          {filter-type (merge value {:user (when (integer? user) user)})})
           :consensus   {filter-type value}
           :has-label   {filter-type value}
           :source      (when (not-empty (:source-ids value))
                          {filter-type value})
           :prediction  (let [{:keys [label-id label-value direction score]} value]
                          (when (and label-id ((complement nil?) label-value) direction score)
                            (when-let [score (parse-number score)]
                              {filter-type (assoc value :score score)})))
           nil)
         (map-values #(dissoc % :editing?)))))

(defn- process-all-filters-input [ifilters]
  (->> ifilters
       (mapv #(let [[[_filter-type value]] (vec %)
                    {:keys [editing?]} value]
                (if editing?
                  (process-filter-input %)
                  %)))
       (filterv some?)))

(defn- sync-filters-input [db context]
  (let [filters (al/get-state db context [:filters])
        new-filters (->> (al/get-state db context [:inputs :filters])
                         (process-all-filters-input))]
    (cond-> (-> (al/set-state db context [:filters] new-filters)
                (al/set-state context [:inputs :filters] nil))
      (not= filters new-filters)
      (-> (al/set-state context [:display-offset] 0)
          (al/set-state context [:active-article] nil)))))

(reg-event-fx ::sync-filters-input [trim-v]
              (fn [{:keys [db]} [context]]
                {:db (sync-filters-input db context)
                 ::al/reload-list [context :transition]}))

(reg-event-db ::add-filter [trim-v]
              (fn [db [context filter-type]]
                (let [filters (get-filters-input db context)
                      new-filter (create-filter db filter-type)]
                  (if (in? filters new-filter)
                    db
                    (al/set-state db context (concat [:inputs :filters])
                                  (-> [new-filter]
                                      (concat filters) vec))))))

(reg-sub ::editing-filter?
         (fn [[_ context]]
           [(subscribe [::al/filters context])
            (subscribe [::filters-input context])])
         (fn [[active input]]
           (boolean (some #(not (in? active %)) input))))

(reg-event-db ::set-text-search [trim-v]
              (fn [db [context value]]
                (let [current (al/get-state db context [:text-search])]
                  (cond-> (-> (al/set-state db context [:text-search] value)
                              (al/set-state context [:inputs :text-search] nil))
                    (not= value current)
                    (-> (al/set-state context [:display-offset] 0)
                        (al/set-state context [:active-article] nil))))))

(defn TextSearchInput [context]
  (let [input (subscribe [::inputs context [:text-search]])
        set-input #(dispatch-sync [::al/set context [:inputs :text-search] %])
        curval @(subscribe [::al/get context [:text-search]])
        synced? (or (nil? @input) (= @input curval))]
    [:div.ui.fluid.left.icon.input {:class (css [(not synced?) "loading"])}
     [:input
      {:id "article-search"
       :type "text"
       :value (or @input curval)
       :placeholder "Search articles"
       :on-change (util/on-event-value
                   #(do (set-input %)
                        (-> (fn [] (let [value (not-empty %)
                                         later-value (if (empty? @input) nil @input)]
                                     (when (= value later-value)
                                       (dispatch-sync [::set-text-search context value]))))
                            (js/setTimeout 1000))))}]
     [:i.search.icon]]))

(defn- filter-dropdown-params [multiple? disabled]
  (let [touchscreen? @(subscribe [:touchscreen?])]
    (merge {:class "filter-dropdown", :fluid true, :disabled (boolean disabled)}
           (if multiple?
             {:multiple true, :selection true, :search (not touchscreen?)}
             {:button true, :floating true}))))

(defn- from-data-value [v & [read-value]]
  (cond (= v "__nil")            nil
        (= v "__boolean_true")   true
        (= v "__boolean_false")  false
        read-value               (read-value v)
        :else                    v))

(defn- to-data-value [v]
  (cond (nil? v)                       "__nil"
        (true? v)                      "__boolean_true"
        (false? v)                     "__boolean_false"
        (or (symbol? v) (keyword? v))  (name v)
        :else                          (str v)))

(defn- FilterDropdown [options option-name active on-change multiple? read-value
                       & [{:keys [disabled]}]]
  [S/Dropdown
   (merge (filter-dropdown-params multiple? disabled)
          {:placeholder (when (nil? (some-> active (from-data-value read-value)))
                          (option-name nil))
           :options (for [[i x] (map-indexed vector options)]
                      {:key (str i "-" x)
                       :value (to-data-value x)
                       :text (option-name x)})
           :value (to-data-value active)
           :on-change (when on-change
                        (fn [_event x]
                          (on-change (-> (.-value x)
                                         (from-data-value read-value)))))})])

(defn- FilterDropdownPlaceholder
  "Renders a disabled placeholder for a FilterDropdown component."
  [multiple?]
  [S/Dropdown (merge (filter-dropdown-params multiple? true)
                     {:placeholder ""})])

(defn- SelectUserDropdown [_context value on-change multiple?]
  (let [user-ids @(subscribe [:project/member-user-ids nil true])
        self-id @(subscribe [:self/user-id])
        value (->> (if (= value :self) self-id value)
                   (when-test (in? user-ids)))]
    [FilterDropdown
     (concat [nil] user-ids)
     #(if (or (nil? %) (= :any %))
        "Any User"
        @(subscribe [:user/username %]))
     value on-change multiple?
     #(parse-integer %)]))

(defn describe-source [source-id]
  (when source-id
    (let [{:keys [article-count]} @(subscribe [:project/sources source-id])
          source-type @(subscribe [:source/display-type source-id])
          description (or (some-> @(subscribe [:source/display-info source-id])
                                  (util/ellipsis-middle 70 "[.....]"))
                          (str source-id))]
      (space-join [(wrap-parens article-count :parens "[]")
                   (wrap-parens source-type :parens "[]")
                   description]))))

(defn- SelectSourceDropdown [_context value on-change multiple?]
  (let [source-ids @(subscribe [:project/source-ids true])
        value (when (sequential? value) (not-empty value))]
    [FilterDropdown
     source-ids
     #(if (nil? %) "Select Source" (describe-source %))
     (first value)
     on-change
     multiple?
     parse-integer]))

(defn- SelectLabelDropdown [_context value on-change]
  (let [label-ids @(subscribe [:project/label-ids])
        {:keys [labels]} @(subscribe [:project/raw])]
    [FilterDropdown
     (concat [nil] label-ids)
     #(if (nil? %) "Any Label" (get-in labels [% :short-label]))
     value on-change false
     #(if (in? label-ids (to-uuid %))
        (to-uuid %) nil)]))

(defn- SelectFromLabelsDropdown [_context label-ids value on-change & [opts]]
  (let [{:keys [labels]} @(subscribe [:project/raw])]
    [FilterDropdown
     label-ids
     #(if (nil? %) "Any Label" (get-in labels [% :short-label]))
     value on-change false
     #(if (in? label-ids (to-uuid %))
        (to-uuid %) nil)
     opts]))

(defn- LabelValueDropdown [_context label-id value on-change include-nil?]
  (let [boolean? (= "boolean" @(subscribe [:label/value-type nil label-id]))
        categorical? (= "categorical" @(subscribe [:label/value-type nil label-id]))
        all-values @(subscribe [:label/all-values nil label-id])
        entries (cond->> (cond boolean?      [nil true false]
                               categorical?  (concat [nil] all-values)
                               :else         nil)
                  (not include-nil?) (filterv some?))]
    (if (empty? entries)
      [FilterDropdownPlaceholder false]
      [FilterDropdown
       entries
       #(cond (nil? %)   "Any Value"
              (true? %)  "Yes"
              (false? %) "No"
              :else      %)
       value on-change false
       identity])))

(defn- ContentTypeDropdown [_context value on-change]
  [FilterDropdown
   [nil :labels :annotations]
   #(case %
      :labels "Labels"
      :annotations "Annotations"
      "Any")
   value on-change false
   keyword])

(defn- BooleanDropdown [_context value on-change]
  [FilterDropdown
   [nil true false]
   #(cond (= % true)  "Yes"
          (= % false) "No"
          :else       "Any")
   value on-change false
   #(cond (= % "true")  true
          (= % "false") false
          :else         nil)])

(defn- ConsensusStatusDropdown [_context value on-change]
  [FilterDropdown
   [nil :single :determined :conflict :consistent :resolved]
   #(if (keyword? %)
      (str/capitalize (name %))
      "Any")
   value on-change false
   #(some-> % keyword)])

(defn- SortByDropdown [_context value on-change]
  [FilterDropdown
   [:content-updated :article-added]
   #(case %
      :content-updated "Content Updated"
      :article-added "Article Added")
   value on-change false
   #(some-> % keyword)])

(defn- ifilter-valid? [ifilter]
  (not-empty (process-filter-input ifilter)))

(defn- FilterEditButtons [context filter-idx]
  (let [ifilter (-> @(subscribe [::filters-input context])
                    (nth filter-idx) ifilter-valid?)
        valid? (-> ifilter ifilter-valid?)
        filters (vec @(subscribe [::al/filters]))
        unchanged? (when (contains? filters filter-idx)
                     (= (process-filter-input ifilter)
                        (process-filter-input (nth filters filter-idx))))
        can-save? (and valid? (not unchanged?))]
    [:div.ui.small.form
     [:div.field.edit-buttons>div.fields
      [:div.eight.wide.field
       [:button.ui.tiny.fluid.labeled.icon.positive.button
        {:class (css [(not can-save?) "disabled"])
         :on-click (when can-save?
                     (util/wrap-user-event #(dispatch [::sync-filters-input context])))}
        [:i.circle.check.icon] "Save"]]
      [:div.eight.wide.field
       [:button.ui.tiny.fluid.labeled.icon.button
        {:on-click (util/wrap-user-event #(dispatch [::reset-filters-input context]))}
        [:i.times.icon] "Cancel"]]]]))

(defmulti FilterEditorFields
  (fn [_context _filter-idx ifilter _update-filter]
    (-> ifilter keys first)))

(defmethod FilterEditorFields :default [_context _filter-idx ifilter _update-filter]
  (let [[[ftype _value]] (vec ifilter)]
    [:div.ui.small.form
     [:div.sixteen.wide.field
      [:button.ui.tiny.fluid.button
       (space-join ["editing filter" (some-> ftype pr-str wrap-parens)])]]]))

(defmethod FilterEditorFields :has-user [context _filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [user content confirmed]} value]
    [:div.ui.small.form
     [:div.field>div.fields
      [:div.eight.wide.field
       [:label "User"]
       [SelectUserDropdown context user
        (fn [new-value] (update-filter #(assoc % :user new-value)))
        false]]
      [:div.eight.wide.field
       [:label "Content Type"]
       [ContentTypeDropdown context content
        (fn [new-value] (update-filter #(assoc % :content new-value)))]]]
     (when (or (= content :labels) (in? [true false] confirmed))
       [:div.field>div.fields
        [:div.eight.wide.field
         [:label "Confirmed"]
         [BooleanDropdown context confirmed
          (fn [new-value] (update-filter #(assoc % :confirmed new-value)))]]])]))

(defmethod FilterEditorFields :source [context _filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [source-ids]} value]
    [:div.ui.small.form
     [:div.field>div.fields
      [:div.sixteen.wide.field
       [:label "Article Source"]
       [SelectSourceDropdown context source-ids
        (fn [new-value] (update-filter #(assoc % :source-ids (when new-value [new-value]))))
        false]]]]))

(defmethod FilterEditorFields :has-label [context _filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [label-id users values inclusion confirmed]} value]
    [:div.ui.small.form
     [:div.field>div.fields
      [:div.eight.wide.field
       [:label "Label"]
       [SelectLabelDropdown context label-id
        (fn [v] (update-filter #(merge % {:label-id v :values nil})))]]
      [:div.eight.wide.field
       [:label "User"]
       [SelectUserDropdown context (first users)
        (fn [v] (update-filter #(assoc % :users (if (nil? v) nil [v]))))
        false]]]
     [:div.field>div.fields
      [:div.eight.wide.field
       [:label "Answer Value"]
       [LabelValueDropdown context label-id (first values)
        (fn [v] (update-filter #(assoc % :values (if (nil? v) nil [v]))))
        true]]
      [:div.eight.wide.field
       [:label "Inclusion"]
       [BooleanDropdown context inclusion
        (fn [v] (update-filter #(assoc % :inclusion v)))]]]
     [:div.field>div.fields
      [:div.eight.wide.field
       [:label "Confirmed"]
       [BooleanDropdown context confirmed
        (fn [v] (update-filter #(assoc % :confirmed v)))]]]]))

(defmethod FilterEditorFields :consensus [context _filter-idx ifilter update-filter]
  (let [[[_ {:keys [status inclusion]}]] (vec ifilter)]
    [:div.ui.small.form>div.field>div.fields
     [:div.eight.wide.field
      [:label "Consensus Status"]
      [ConsensusStatusDropdown context status
       (fn [new-value] (update-filter #(assoc % :status new-value)))]]
     [:div.eight.wide.field
      [:label "Inclusion"]
      [BooleanDropdown context inclusion
       (fn [new-value] (update-filter #(assoc % :inclusion new-value)))]]]))

(defmethod FilterEditorFields :prediction [context _filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [label-id label-value direction score]} value
        get-label-type #(deref (subscribe [:label/value-type nil %]))
        label-ids (->> @(subscribe [:project/label-ids])
                       (filter #(contains? predictable-label-types (get-label-type %))))]
    [:div.ui.small.form
     [:div.field>div.fields
      [:div.eight.wide.field
       [:label "Label"]
       [SelectFromLabelsDropdown context label-ids label-id
        (fn [v]
          (let [default-value (if (= (get-label-type v) "boolean")
                                true
                                (first @(subscribe [:label/all-values nil v])))]
            (update-filter #(merge % {:label-id v :label-value default-value}))))]]
      [:div.eight.wide.field
       [:label "Answer Value"]
       [LabelValueDropdown context label-id label-value
        (fn [v] (update-filter #(assoc % :label-value v)))
        false]]]
     [:div.field>div.fields
      [:div.eight.wide.field
       [:label "Direction"]
       [FilterDropdown
        [:above :below]
        #(-> % name str/capitalize)
        direction
        (fn [v] (update-filter #(assoc % :direction v)))
        false
        #(keyword %)]]
      [:div.eight.wide.field
       [:label "Prediction Score"]
       [:div.ui.fluid.right.icon.input
        [:input {:type "text"
                 :class (str "filter-input--" "score")
                 :value (str score)
                 :on-change (util/on-event-value (fn [v] (update-filter #(assoc % :score v))))}]
        [:i.percent.icon]]]]]))

(defn- FilterEditNegationControl [_context _filter-idx ifilter update-filter]
  (let [[[_ {:keys [negate]}]] (vec ifilter)]
    [:div.ui.small.form>div.field.filter-negation
     [:div.fields>div.sixteen.wide.field>div.ui.fluid.buttons.radio-primary
      [:button.ui.tiny.fluid.button
       {:class (css [(not negate) "primary"])
        :on-click (util/wrap-user-event (fn [] (update-filter #(dissoc % :negate))))}
       "Match"]
      [:button.ui.tiny.fluid.button
       {:class (css [negate "primary"])
        :on-click (util/wrap-user-event (fn [] (update-filter #(assoc % :negate true))))}
       "Exclude"]]]))

(defn- FilterEditElement [context filter-idx]
  (let [ifilter (-> @(subscribe [::filters-input context])
                    (nth filter-idx))
        update-filter #(dispatch [::update-filter-input context filter-idx %])]
    [:div.ui.secondary.segment.edit-filter
     [FilterEditNegationControl context filter-idx ifilter update-filter]
     [FilterEditorFields context filter-idx ifilter update-filter]
     [FilterEditButtons context filter-idx]]))

(defmulti filter-description (fn [_context ftype _value] ftype))

(defmethod filter-description :default [_context ftype _value]
  (space-join ["unknown filter" (some-> ftype pr-str wrap-parens)]))

(defmethod filter-description :has-user [_context _ftype value]
  (let [{:keys [user content confirmed]} value
        confirmed-str (cond (= content :annotations)  nil
                            (true? confirmed)         "confirmed"
                            (false? confirmed)        "unconfirmed"
                            :else                     nil)
        content-str (case content
                      :labels       "labels"
                      :annotations  "annotations"
                      "content")
        user-name (and (integer? user) @(subscribe [:user/username user]))
        user-str (if-not (integer? user) "any user"
                         (space-join ["user" (pr-str user-name)]))]
    (space-join ["has" confirmed-str content-str "from" user-str])))

(defmethod filter-description :source [_context _ftype value]
  (let [{:keys [source-ids]} value]
    (space-join ["is present in source" (->> (mapv describe-source source-ids)
                                             (str/join " OR "))])))

(defmethod filter-description :has-label [_context _ftype value]
  (let [{:keys [label-id users values inclusion confirmed]} value
        confirmed-str (cond (true? confirmed)   "confirmed"
                            (false? confirmed)  "unconfirmed"
                            :else               nil)
        label-str (if (nil? label-id) (space-join ["any" confirmed-str "label"])
                      (space-join [confirmed-str (-> @(subscribe [:label/display nil label-id])
                                                     pr-str wrap-parens)]))
        inclusion-str (case inclusion
                        true   "with positive inclusion"
                        false  "with negative inclusion"
                        nil)
        values-str (when (not-empty values)
                     (space-join [(if inclusion-str "and" "with") "value"
                                  (->> values (map pr-str) (str/join " OR ") wrap-parens)]))
        users-str (if (empty? users) "any user"
                      (space-join ["user" (->> users
                                               (map #(pr-str @(subscribe [:user/username %])))
                                               (str/join " OR ")
                                               wrap-parens)]))]
    (space-join ["has" label-str inclusion-str values-str "from" users-str])))

(defmethod filter-description :consensus [_context _ftype value]
  (let [{:keys [status inclusion]} value
        status-str (cond (= status :determined)  "Consistent OR Resolved"
                         (keyword? status)       (-> status name str/capitalize)
                         :else                   "Any")
        inclusion-str (case inclusion
                        true   "positive inclusion"
                        false  "negative inclusion"
                        nil)]
    (space-join (concat ["has consensus status" (wrap-parens status-str)]
                        (when inclusion-str ["for" inclusion-str])))))

(defmethod filter-description :prediction [_context _ftype value]
  (let [{:keys [label-id label-value direction score]} value]
    (space-join ["has prediction score" (case direction  :above ">", :below "<", "(??)")
                 (some-> score (* 10) int (/ 10.0) (str "%")) "for"
                 (wrap-parens
                  (space-join [(pr-str @(subscribe [:label/display nil label-id]))
                               "=" (pr-str label-value)]))])))

(defn- FilterDescribeElement
  [context filter-idx]
  [:div.describe-filter
   (if (nil? filter-idx)
     [:div.ui.tiny.fluid.button "all articles"]
     (let [filters @(subscribe [::filters-input context])
           fr (nth filters filter-idx)
           [[filter-type value]] (vec fr)
           description (as-> (filter-description context filter-type value) s
                         (if (:negate value)
                           (str "NOT [" s "]")
                           s))]
       [:div.ui.center.aligned.middle.aligned.grid
        [:div.row
         [:div.middle.aligned.two.wide.column.delete-filter
          [:div.ui.tiny.fluid.icon.button
           {:on-click #(dispatch [::delete-filter context filter-idx])}
           [:i.times.circle.icon]]]
         [:div.middle.aligned.fourteen.wide.column.describe-filter
          [:button.ui.tiny.fluid.button
           {:on-click #(dispatch [::edit-filter context filter-idx])}
           description]]]]))])

(defn- TextSearchDescribeElement [context]
  (let [text-search @(subscribe [::al/get context [:text-search]])]
    (when ((every-pred string? not-empty) text-search)
      [:div.describe-filter>div.ui.center.aligned.middle.aligned.grid>div.row
       [:div.middle.aligned.two.wide.column.delete-filter
        [:div.ui.tiny.fluid.icon.button {:on-click #(dispatch-sync [::set-text-search context nil])}
         [:i.times.icon]]]
       [:div.middle.aligned.fourteen.wide.column
        [:button.ui.tiny.fluid.button {:on-click #(.focus ($ "#article-search"))}
         (space-join ["text contains" (->> (str/split text-search #"[ \t\r\n]+")
                                           (map pr-str)
                                           (str/join " AND "))])]]])))

(defn- NewFilterElement [context]
  (when (not @(subscribe [::editing-filter? context]))
    (let [items [[:source "Source" "list"]
                 [:consensus "Consensus" "users"]
                 [:has-label "Labels" "tags"]
                 [:has-user "User Content" "user"]
                 [:prediction "Prediction" "chart bar"]
                 #_ [:has-annotation "Annotation" "quote left"]]]
      [:div.ui.small.form.new-filter
       [S/Dropdown {:button true, :fluid true, :floating true
                    :class "primary add-filter"
                    :text "Add Filter"
                    :options (for [[i [ftype label icon]] (map-indexed vector items)]
                               {:key i
                                :value (name ftype)
                                :icon icon
                                :text (str label)})
                    :on-change (fn [_event x]
                                 (let [v (.-value x)]
                                   (some-> v not-empty keyword
                                           (#(dispatch-sync [::add-filter context %])))))}]])))

(reg-event-fx :article-list/load-settings [trim-v]
              (fn [{:keys [db]}
                   [context {:keys [filters display sort-by sort-dir text-search]}]]
                {:db (-> (al/set-state db context [:display] display)
                         (al/set-state context [:inputs :filters] filters)
                         (al/set-state context [:sort-by] sort-by)
                         (al/set-state context [:sort-dir] sort-dir)
                         (al/set-state context [:text-search] text-search)
                         (al/set-state context [:inputs :text-search] nil)
                         (sync-filters-input context))
                 ::al/reload-list [context :transition]}))

(reg-event-fx :article-list/load-preset [trim-v]
              (fn [_ [context _preset-name]]
                (let [preset (:self @(subscribe [:articles/filter-presets]))]
                  {:dispatch [:article-list/load-settings context preset]})))

(defn- WrapFilterDisabled [content enabled? message width]
  (if enabled?
    content
    [S/Popup {:class "filters-tooltip"
              :hoverable false
              :inverted true
              :trigger (r/as-element [:div content])
              :content (r/as-element [:div {:style {:min-width width}} message])}]))

(defn- FilterPresetsForm [context]
  (let [filters @(subscribe [::filters-input context])
        text-search @(subscribe [::al/get context [:text-search]])
        presets @(subscribe [:articles/filter-presets])
        make-button
        (fn [pkey text _icon-class enabled?]
          (let [preset (get presets pkey)]
            [:button.ui.tiny.fluid.button
             {:class (css [(not enabled?) "disabled"
                           (filter-sets-equal? filters (:filters preset)) "grey"])
              :on-click (when enabled?
                          (util/wrap-user-event
                           #(dispatch-sync [:article-list/load-settings
                                            context (merge preset {:text-search text-search})])))}
             #_ [:i {:class (str icon-class " icon")}]
             text]))
        member? @(subscribe [:self/member?])]
    [:div.ui.segments>div.ui.segment.filter-presets
     [:form.ui.small.form {:on-submit (util/wrap-prevent-default #(do nil))}
      [:div.sixteen.wide.field
       [:label "Presets"]
       [:div.ui.three.column.grid
        [:div.column (WrapFilterDisabled [make-button :self "Self" "user" member?]
                                         member? "Not a member of this project" "15em")]
        [:div.column [make-button :content "Labeled" "tags" true]]
        [:div.column [make-button :inclusion "Inclusion" "circle plus" true]]]]]]))

(defn- DisplayOptionsForm [context]
  (let [options @(subscribe [::al/display-options context])
        on (reduce (fn [s [k v]] (if v (conj s k) s)) #{} options)
        cursor (r/wrap
                on
                (fn [on']
                  (doseq [[k v] options]
                    (when-not (= v (contains? on' k))
                      (dispatch-sync [::al/set-display-option
                                      context k (contains? on' k)])))))]
    [ui/MultiSelect
     {:cursor cursor
      :label "Display Options"
      :options
      [[:show-labels "Labels"]
       [:show-notes "Notes"]
       [:show-inclusion "Inclusion"]
       [:show-unconfirmed "Unconfirmed"]
       [:self-only "Self Only"]]}]))

(defn- SortOptionsForm [context]
  (let [sort-by @(subscribe [::al/sort-by context])
        sort-dir @(subscribe [::al/sort-dir context])]
    [:div.ui.segment.sort-options>div.ui.small.form>div.field
     [:label "Sort By"]
     [:div.fields
      [:div.nine.wide.field
       [SortByDropdown context sort-by
        #(dispatch [::al/set context [:sort-by] %])]]
      [:div.seven.wide.field>div.ui.tiny.fluid.buttons
       [:button.ui.icon.button
        {:class (css [(= sort-dir :asc) "grey"])
         :on-click #(do (dispatch-sync [::al/set context [:sort-dir] :asc])
                        (al/reload-list context :transition))}
        [:i.arrow.up.icon]]
       [:button.ui.icon.button
        {:class (css [(= sort-dir :desc) "grey"])
         :on-click #(do (dispatch-sync [::al/set context [:sort-dir] :desc])
                        (al/reload-list context :transition))}
        [:i.arrow.down.icon]]]]]))

(defn ^:unused ResetReloadForm [context]
  (let [recent-nav-action @(subscribe [::al/get context [:recent-nav-action]])
        any-filters? (not-empty @(subscribe [::filters-input context]))
        display @(subscribe [::al/display-options (al/cached context)])
        can-reset? (or any-filters? (not= display (:display al/default-options)))
        reloading? (= recent-nav-action :refresh)]
    [:div.ui.small.form>div.sixteen.wide.field>div.ui.grid.reset-reload
     [:div.thirteen.wide.column
      [:button.ui.small.fluid.icon.labeled.button
       {:on-click (when can-reset? #(do (dispatch [::al/reset-all context])
                                        (al/reload-list context :transition)
                                        (dispatch [::reset-filters-input context])))
        :class (css [(not can-reset?) "disabled"])}
       [:i.times.icon] "Reset All"]]
     [:div.three.wide.column
      [:button.ui.small.fluid.icon.button
       {:class (css [reloading? "loading"])
        :on-click (util/wrap-user-event #(al/reload-list context :refresh))}
       [:i.repeat.icon]]]]))

#_
(defn- ArticleListFiltersRow [context]
  (let [get-val #(deref (subscribe [::al/get context %]))
        recent-nav-action (get-val [:recent-nav-action])
        {:keys [expand-filters] :as display-options} @(subscribe [::al/display-options context])
        loading? (= recent-nav-action :refresh)
        view-button (fn [option-key label]
                      (let [status (get display-options option-key)]
                        [:button.ui.small.labeled.icon.button
                         {:on-click (util/wrap-user-event
                                     #(dispatch [::al/set-display-option
                                                 context option-key (not status)]))}
                         [:i {:class (css [status "green" :else "grey"] "circle icon")}]
                         label]))]
    [:div.ui.segments.article-filters.article-filters-row
     [:div.ui.secondary.middle.aligned.grid.segment.filters-minimal>div.row
      [:div.one.wide.column.medium-weight.filters-header "Filters"]
      [:div.nine.wide.column.filters-summary
       [:span #_ (doall (for [filter-idx ...]
                          [FilterDescribeElement context filter-idx]))]]
      [:div.six.wide.column.right.aligned.control-buttons
       [:button.ui.small.icon.button
        {:class (css [loading? "loading"])
         :on-click (util/wrap-user-event #(al/reload-list context :refresh))}
        [:i.repeat.icon]]
       [:button.ui.small.icon.button
        {:on-click (util/wrap-user-event #(do (dispatch [::al/reset-all context])
                                              (al/reload-list context :transition)))}
        [:i.times.icon]]]]
     [:div.ui.secondary.segment.no-padding
      [:button.ui.tiny.fluid.icon.button
       {:style (cond-> {:border-top-left-radius "0"
                        :border-top-right-radius "0"}
                 expand-filters (merge {:border-bottom-left-radius "0"
                                        :border-bottom-right-radius "0"}))
        :on-click (util/wrap-user-event
                   #(dispatch [::al/set-display-option
                               context :expand-filters (not expand-filters)]))}
       [:i {:class (css "chevron" [expand-filters "up" :else "down"] "icon")}]]]
     (when expand-filters
       [:div.ui.secondary.segment.filters-content>div.ui.small.form>div.field>div.fields
        [:div.four.wide.field
         [:label "Text search"]
         [TextSearchInput context]]
        [:div.six.wide.field
         [:label "View Options"]
         [view-button :show-inclusion "Inclusion"]
         [view-button :show-labels "Labels"]
         [view-button :show-notes "Notes"]]
        [:div.six.wide.field]])]))

(defn- FilterColumnCollapsed [context]
  [:div.ui.segments.article-filters.article-filters-column.collapsed
   {:on-click (util/wrap-user-event
               #(dispatch-sync [::al/set-display-option context :expand-filters true]))}
   [:div.ui.center.aligned.header.segment "Filters"]
   [:div.ui.one.column.center.aligned.grid.segment.expand-filters
    [:div.column>i.fitted.angle.double.right.icon]]])



(def export-type-default-filters
  {:group-answers    [{:has-label {:confirmed true}}
                      {:consensus {:status :conflict, :negate true}}]
   :user-answers     [{:has-label {:confirmed true}}]
   :endnote-xml      []
   :articles-csv     []
   :annotations-csv  [{:has-user {:content :annotations}}]})

(defn- ExportFiltersInfo [context]
  (let [article-count @(al/sub-article-count (al/cached context))]
    [:div.ui.two.column.middle.aligned.grid.segment.export-filters-info
     [:div.left.aligned.column>h5.ui.header "Active Filters"]
     [:div.right.aligned.column
      [:div.ui.label (str article-count " articles")]]]))

(def default-csv-separator "|||")
(def csv-separator-options ["|||" ";;;" ","])

(reg-sub ::csv-separator
         (fn [db [_ context]]
           (or (ui-state/get-panel-field db [:csv-separator] (:panel context))
               default-csv-separator)))

(reg-event-db ::set-csv-separator
              (fn [db [_ context value]]
                (ui-state/set-panel-field db [:csv-separator] value (:panel context))))

(defn- SelectSeparatorDropdown [context value]
  [S/Dropdown {:fluid true
               :floating true
               :selection true
               :value value
               :options (for [x csv-separator-options]
                          {:key x
                           :value x
                           :text (str x)
                           :content (r/as-element [:div {:style {:width "100%"}} (str x)])})
               :on-change (fn [_event x]
                            (dispatch [::set-csv-separator context (.-value x)]))}])

(defn- ExportSettingsFields [context _export-type file-format separator]
  (->> [(when (= file-format "CSV")
          (fn []
            [:div.field.export-setting {:key :csv-separator}
             [:div.fields>div.sixteen.wide.field
              [ui/UiHelpTooltip [:label "Value Separator" [UiHelpIcon]]
               :help-content
               ["Internal separator for multiple values inside a column, such as label answers."]]
              [SelectSeparatorDropdown context separator]]]))]
       (remove nil?)))

(defn- ExportTypeForm [context export-type title file-format]
  (let [project-id @(subscribe [:active-project-id])
        options (merge @(subscribe [::al/export-filter-args (al/cached context)])
                       {:separator @(subscribe [::csv-separator])})
        action [:project/generate-export project-id export-type options]
        running? (action/running? action)
        entry @(subscribe [:project/export-file project-id export-type options])
        {:keys [filename url error]} entry
        {:keys [expand-export]} @(subscribe [::al/display-options context])
        expanded? (= expand-export (name export-type))
        file? (and entry (not error))
        defaults? (filter-sets-equal? @(subscribe [::al/filters context])
                                      (get export-type-default-filters export-type))]
    [:div.ui.segments.export-type
     {:class (css (name export-type) [expanded? "expanded" :else "collapsed"])}
     [:a.ui.middle.aligned.two.column.grid.secondary.segment.expander
      {:on-click (util/wrap-user-event
                  #(dispatch-sync [::al/set-display-option context :expand-export
                                   (if expanded? true (name export-type))]))}
      [:div.left.aligned.column>h5.ui.header title]
      [:div.right.aligned.column>div.ui.small.grey.label file-format]]
     (when expanded?
       [:div.ui.secondary.segment.expanded>div.ui.small.form.export-type
        (doall (map-indexed (fn [i x] (when x ^{:key i} [x]))
                            (ExportSettingsFields
                              context export-type file-format
                              (:separator options))))
        [:div.field>div.fields.export-actions
         [:div.eight.wide.field
          [:button.ui.tiny.fluid.primary.labeled.icon.button
           {:on-click (when-not running? (util/wrap-user-event #(dispatch [:action action])))
            :class (css [running? "loading"])}
           [:i.hdd.icon] "Generate"]]
         [:div.eight.wide.field
          [:button.ui.tiny.fluid.right.labeled.icon.button
           {:on-click (when-not defaults?
                        (util/wrap-user-event
                         #(dispatch [:article-list/load-export-settings nil export-type false])))
            :class (css [defaults? "disabled"])}
           [:i {:class (css [defaults? "circle check outline" :else "exchange"] "icon")}]
           "Set Defaults"]]]
        (when-not error
          [:div.field>div.ui.center.aligned.segment.file-download
           (cond running?  [:span "Generating file..."]
                 file?     [:a {:href url :target "_blank" :download filename}
                            [:i.outline.file.icon] " " filename]
                 :else     [:span "<Generate file to download>"])])
        (when error
          [:div.field>div.ui.negative.message
           (or (-> error :message)
               "Sorry, an error occurred while generating the file.")])])]))

(defn- ExportDataForm [context]
  (let [{:keys [expand-export]} @(subscribe [::al/display-options context])]
    [:div.ui.segments.export-data
     [:div.ui.middle.aligned.grid.segment.expander
      {:on-click (util/wrap-user-event
                  #(dispatch-sync [::al/set-display-option
                                   context :expand-export (not expand-export)]))}
      [:div.ten.wide.left.aligned.column
       [:h5.ui.header "Export Data"]]
      [:div.six.wide.right.aligned.column
       (if expand-export [:i.chevron.up.icon] [:i.chevron.down.icon])]]
     (when expand-export
       [:div.ui.segment.export-data-content
        [:h6.ui.right.aligned.header
         {:style {:margin "6px 0 8px 0"}}
         [:a {:href @(subscribe [:project/uri nil "/export"])}
          "What do these mean?"]]
        [ExportFiltersInfo context]
        [ExportTypeForm context :endnote-xml "Articles" "EndNote XML"]
        [ExportTypeForm context :articles-csv "Articles" "CSV"]
        [ExportTypeForm context :group-answers "Article Answers" "CSV"]
        [ExportTypeForm context :user-answers "User Answers" "CSV"]
        [ExportTypeForm context :annotations-csv "Annotations" "CSV"]])]))

(defn- FilterColumnElement [context]
  (let [project-id @(subscribe [:active-project-id])
        active-filters @(subscribe [::al/filters context])
        input-filters @(subscribe [::filters-input context])]
    [:div.article-filters.article-filters-column.expanded
     [:a.ui.fluid.primary.left.labeled.icon.button
      {:href (project-uri project-id "/add-articles")}
      [:i.list.icon] "Add/Manage Articles"]
     [:div.ui.segments
      [:div.ui.segment.filters-content
       [:div.ui.small.form {:on-submit (util/no-submit)}
        [:div.sixteen.wide.field
         [:label "Article Filters"]
         [:div.inner
          [NewFilterElement context]
          [TextSearchDescribeElement context]
          (if (empty? input-filters)
            [FilterDescribeElement context nil]
            (doall (map-indexed (fn [filter-idx fr]
                                  (if (in? active-filters fr)
                                    ^{:key [:filter-text filter-idx]}
                                    [FilterDescribeElement context filter-idx]
                                    ^{:key [:filter-editor filter-idx]}
                                    [FilterEditElement context filter-idx]))
                                input-filters)))
          #_ [ResetReloadForm context]]]]]
      [SortOptionsForm context]
      [:div.ui.segment.reset-all
       [:button.ui.small.fluid.right.labeled.icon.button
        {:style {:margin-top "2px"}
         :on-click (util/wrap-user-event #(dispatch [::al/reset-all context]))}
        [:i.times.icon] "Reset All"]]]
     [DisplayOptionsForm context]
     [FilterPresetsForm context]
     [ExportDataForm]]))

(defn ArticleListFiltersColumn [context expanded?]
  [:div (if expanded?
          [FilterColumnElement context]
          [FilterColumnCollapsed context])])
