(ns sysrev.views.article-list.filters
  (:require [clojure.string :as str]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer
             [active-panel active-project-id]]
            [sysrev.state.ui :as ui-state]
            [sysrev.shared.article-list :refer
             [is-resolved? resolved-answer is-conflict? is-single? is-consistent?]]
            [sysrev.views.components :as ui]
            [sysrev.views.article-list.base :as al]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in? map-values]]))

(def group-statuses
  [:single :determined :conflict :consistent :resolved])

(reg-sub
 ::inputs
 (fn [[_ context path]]
   [(subscribe [::al/get context (concat [:inputs] path)])])
 (fn [[input-val]] input-val))

(defn create-filter [filter-type]
  {filter-type
   (merge
    (case filter-type
      :has-user       {:user nil
                       :content nil
                       :confirmed nil}
      :has-label      {:label-id nil
                       :users nil
                       :values nil
                       :inclusion nil
                       :confirmed nil}
      :consensus      {:status nil
                       :inclusion nil})
    {:editing? true})})

(defn filter-presets []
  (let [self-id @(subscribe [:self/user-id])
        overall-id @(subscribe [:project/overall-label-id])]
    {:self
     {:filters [{:has-user {:user self-id
                            :content nil
                            :confirmed nil}}]
      :display {:self-only true
                :show-inclusion false
                :show-labels true
                :show-notes true
                :show-unconfirmed true}}

     :content
     {:filters [{:has-user {:user nil
                            :content nil
                            :confirmed nil}}]
      :display {:self-only false
                :show-inclusion false
                :show-labels true
                :show-notes true}}

     :inclusion
     {:filters [{:consensus {:status nil
                             :inclusion nil}}]
      :display {:self-only false
                :show-inclusion true
                :show-labels false
                :show-notes false}}}))

(defn- reset-filters-input [db context]
  (al/set-state db context [:inputs :filters] nil))

(reg-event-db
 ::reset-filters-input
 [trim-v]
 (fn [db [context]]
   (reset-filters-input db context)))

(defn- get-filters-input [db context]
  (let [input (al/get-state db context [:inputs :filters])
        active (al/get-active-filters db context)]
    (if (nil? input) active input)))

(reg-sub
 ::filters-input
 (fn [[_ context]]
   [(subscribe [::inputs context [:filters]])
    (subscribe [::al/filters context])])
 (fn [[input active]]
   (if (nil? input) active input)))

(reg-event-db
 ::update-filter-input
 [trim-v]
 (fn [db [context filter-idx update-fn]]
   (let [ifilter (al/get-state db context [:inputs :filters filter-idx])
         filter-type (first (keys ifilter))
         value (first (vals ifilter))]
     (al/set-state db context [:inputs :filters filter-idx]
                   {filter-type (update-fn value)}))))

(defn- remove-null-filters [db context]
  (let [ifilters (al/get-state db context [:inputs :filters])
        filters (al/get-state db context [:filters])]
    (cond-> db
      (not-empty ifilters)
      (al/set-state context [:inputs :filters]
                    (->> ifilters (remove nil?) vec))
      (not-empty filters)
      (al/set-state context [:filters]
                    (->> filters (remove nil?) vec)))))

(defn- delete-filter [db context filter-idx]
  (let [ifilters (al/get-state db context [:inputs :filters])
        filters (al/get-state db context [:filters])]
    (cond-> db
      (contains? ifilters filter-idx)
      (al/set-state context [:inputs :filters filter-idx] nil)
      (contains? filters filter-idx)
      (al/set-state context [:filters filter-idx] nil)
      true
      (remove-null-filters context))))

(reg-event-db
 ::delete-filter
 [trim-v]
 (fn [db [context filter-idx]]
   (delete-filter db context filter-idx)))

(defn- edit-filter [db context filter-idx]
  (let [filters (al/get-state db context [:filters])]
    (if (and (vector? filters)
             (contains? filters filter-idx))
      (al/set-state
       db context [:inputs :filters]
       (vec (map-indexed
             (fn [i entry]
               (let [[[fkey fval]] (vec entry)]
                 (if (= i filter-idx)
                   {fkey (assoc fval :editing? true)}
                   {fkey (dissoc fval :editing?)})))
             filters)))
      db)))

(reg-event-db
 ::edit-filter [trim-v]
 (fn [db [context filter-idx]]
   (edit-filter db context filter-idx)))

;; TODO: is this needed?
(defn- process-filter-input [ifilter]
  (let [[[filter-type value]] (vec ifilter)]
    (->> (case filter-type
           :has-user
           (let [{:keys [user content confirmed]} value]
             {filter-type
              (merge value
                     {:user (if (integer? user) user nil)})})

           :consensus
           {filter-type value}

           :has-label
           {filter-type value}

           nil)
         (map-values #(dissoc % :editing?)))))

(defn- process-all-filters-input [ifilters]
  (->> ifilters
       (mapv #(let [[[filter-type value]] (vec %)
                    {:keys [editing?]} value]
                (if editing?
                  (process-filter-input %)
                  %)))
       (filterv (comp not nil?))))

(defn- sync-filters-input [db context]
  (let [filters (al/get-state db context [:filters])
        new-filters (->> (al/get-state db context [:inputs :filters])
                         (process-all-filters-input))]
    (cond-> (-> (al/set-state db context [:filters] new-filters)
                (al/set-state context [:inputs :filters] nil))
      (not= filters new-filters)
      (-> (al/set-state context [:display-offset] 0)
          (al/set-state context [:active-article] nil)))))

(reg-event-fx
 ::sync-filters-input [trim-v]
 (fn [{:keys [db]} [context]]
   {:db (sync-filters-input db context)
    ::al/reload-list [context :transition]}))

(reg-event-db
 ::add-filter
 [trim-v]
 (fn [db [context filter-type]]
   (let [filters (get-filters-input db context)
         new-filter (create-filter filter-type)]
     (if (in? filters new-filter)
       db
       (al/set-state db context (concat [:inputs :filters])
                     (->> [new-filter]
                          (concat filters) vec))))))

(reg-sub
 ::editing-filter?
 (fn [[_ context]]
   [(subscribe [::al/filters context])
    (subscribe [::filters-input context])])
 (fn [[active input]]
   (boolean (some #(not (in? active %)) input))))

(reg-event-db
 ::set-text-search [trim-v]
 (fn [db [context value]]
   (let [current (al/get-state db context [:text-search])]
     (cond-> (-> (al/set-state db context [:text-search] value)
                 (al/set-state context [:inputs :text-search] nil))
       (not= value current)
       (-> (al/set-state context [:display-offset] 0)
           (al/set-state context [:active-article] nil))))))

(defn- TextSearchInput [context]
  (let [input (subscribe [::inputs context [:text-search]])
        set-input #(dispatch-sync [::al/set context [:inputs :text-search] %])
        curval @(subscribe [::al/get context [:text-search]])
        synced? (or (nil? @input) (= @input curval))]
    [:div.ui.fluid.left.icon.input
     {:class (when-not synced? "loading")}
     [:input
      {:type "text"
       :id "article-search"
       :value (or @input curval)
       :placeholder "Search articles"
       :on-change
       (util/wrap-prevent-default
        (fn [event]
          (let [value (-> event .-target .-value)]
            (set-input value)
            (js/setTimeout
             #(let [later-value (if (empty? @input) nil @input)
                    value (if (empty? value) nil value)]
                (when (= value later-value)
                  (dispatch-sync [::set-text-search context value])))
             1000))))}]
     [:i.search.icon]]))

(defn- FilterDropdown [options option-name active on-change multiple? read-value]
  (let [touchscreen? @(subscribe [:touchscreen?])
        to-data-value #(cond (nil? %)          "any"
                             (or (symbol? %)
                                 (keyword? %)) (name %)
                             :else             (str %))]
    [ui/selection-dropdown
     [:div.text (option-name active)]
     (map-indexed
      (fn [i x]
        [:div.item
         {:key [:filter-item i]
          :data-value (to-data-value x)
          :class (when (= x active)
                   "active selected")}
         (option-name x)])
      options)
     {:class
      (cond-> "ui fluid"
        multiple?          (str " multiple")
        (not touchscreen?) (str " search")
        true               (str " selection dropdown"))
      :onChange
      (fn [v t]
        (let [v (cond (= v "any")  nil
                      read-value   (read-value v)
                      :else        v)]
          (when on-change
            (on-change v))))}]))

(defn- SelectUserDropdown [context value on-change multiple?]
  (let [user-ids @(subscribe [:project/member-user-ids nil true])
        self-id @(subscribe [:self/user-id])
        value (if (= value :self)
                (if (and self-id (in? user-ids self-id))
                  self-id
                  nil)
                value)]
    [FilterDropdown
     (concat [nil] user-ids)
     #(if (or (nil? %) (= :any %))
        "Any User"
        @(subscribe [:user/display %]))
     value
     on-change
     multiple?
     #(sutil/parse-integer %)]))

(defn- SelectLabelDropdown [context value on-change]
  (let [label-ids @(subscribe [:project/label-ids])
        {:keys [labels]} @(subscribe [:project/raw])]
    [FilterDropdown
     (concat [nil] label-ids)
     #(if (nil? %)
        "Any Label"
        (get-in labels [% :short-label]))
     value
     on-change
     false
     #(if (in? label-ids (uuid %))
        (uuid %) nil)]))

(defn- LabelValueDropdown [context label-id value on-change]
  (let [boolean? @(subscribe [:label/boolean? label-id])
        categorical? @(subscribe [:label/categorical? label-id])
        all-values @(subscribe [:label/all-values label-id])
        entries
        (cond boolean?      [nil true false]
              categorical?  (concat [nil] all-values)
              :else         [nil])]
    [FilterDropdown
     entries
     #(cond (nil? %)   "Any Value"
            (true? %)  "Yes"
            (false? %) "No"
            :else      %)
     value
     on-change
     false
     (if boolean?
       #(cond (= % "true")  true
              (= % "false") false
              :else         nil)
       identity)]))

(defn- ContentTypeDropdown [context value on-change]
  [FilterDropdown
   ;; TODO: implement :labels and :annotations options
   [nil :labels :annotations]
   #(case %
      :labels "Labels"
      :annotations "Annotations"
      "Any")
   value
   on-change
   false
   #(keyword %)])

(defn- BooleanDropdown [context value on-change]
  [FilterDropdown
   [nil true false]
   #(cond (= % true)  "Yes"
          (= % false) "No"
          :else       "Any")
   value
   on-change
   false
   #(cond (= % "true")  true
          (= % "false") false
          :else         nil)])

(defn- ConsensusStatusDropdown [context value on-change]
  [FilterDropdown
   [nil :single :determined :conflict :consistent :resolved]
   #(if (keyword? %)
      (str/capitalize (name %))
      "Any")
   value
   on-change
   false
   #(some-> % keyword)])

(defn- SortByDropdown [context value on-change]
  [FilterDropdown
   [:content-updated :article-added]
   #(case %
      :content-updated "Content Updated"
      :article-added "Article Added")
   value
   on-change
   false
   #(some-> % keyword)])

(defn- ifilter-valid? [ifilter]
  (not-empty (process-filter-input ifilter)))

(defn- FilterEditButtons [context filter-idx]
  (let [ifilter (-> @(subscribe [::filters-input context])
                    (nth filter-idx) ifilter-valid?)
        valid? (-> ifilter ifilter-valid?)
        filters (vec @(subscribe [::al/filters]))
        unchanged?
        (when (contains? filters filter-idx)
          (= (process-filter-input ifilter)
             (process-filter-input (nth filters filter-idx))))]
    [:div.ui.small.form
     [:div.field.edit-buttons>div.fields
      [:div.eight.wide.field
       [:button.ui.tiny.fluid.labeled.icon.positive.button
        {:class (when (or unchanged? (not valid?)) "disabled")
         :on-click
         (when valid?
           (util/wrap-user-event
            #(dispatch [::sync-filters-input context])))}
        [:i.circle.check.icon]
        "Save"]]
      [:div.eight.wide.field
       [:button.ui.tiny.fluid.labeled.icon.button
        {:on-click
         (util/wrap-user-event
          #(dispatch [::reset-filters-input context]))}
        [:i.times.icon]
        "Cancel"]]]]))

(defmulti FilterEditorFields
  (fn [context filter-idx ifilter update-filter]
    (-> ifilter keys first)))

(defmethod FilterEditorFields :default [context filter-idx ifilter update-filter]
  (let [[[ftype value]] (vec ifilter)]
    [:div.ui.small.form
     [:div.sixteen.wide.field
      [:button.ui.tiny.fluid.button
       (str "editing filter (" (pr-str ftype) ")")]]]))

(defmethod FilterEditorFields :has-user [context filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [user content confirmed]} value]
    [:div.ui.small.form
     [:div.field
      [:div.fields
       [:div.eight.wide.field
        [:label "User"]
        [SelectUserDropdown context user
         (fn [new-value]
           (update-filter #(assoc % :user new-value)))
         false]]
       [:div.eight.wide.field
        [:label "Content Type"]
        [ContentTypeDropdown context content
         (fn [new-value]
           (update-filter #(assoc % :content new-value)))]]]]
     (when (or (= content :labels)
               (in? [true false] confirmed))
       [:div.field
        [:div.fields
         [:div.eight.wide.field
          [:label "Confirmed"]
          [BooleanDropdown
           context confirmed
           (fn [new-value]
             (update-filter #(assoc % :confirmed new-value)))]]]])]))

(defmethod FilterEditorFields :has-label [context filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [label-id users values inclusion confirmed]} value]
    [:div.ui.small.form
     [:div.field
      [:div.fields
       [:div.eight.wide.field
        [:label "Label"]
        [SelectLabelDropdown context label-id
         (fn [new-value]
           (update-filter #(merge % {:label-id new-value
                                     :values nil})))]]
       [:div.eight.wide.field
        [:label "User"]
        [SelectUserDropdown context (first users)
         (fn [new-value]
           (update-filter #(assoc % :users
                                  (if (nil? new-value) nil [new-value]))))]]]]
     [:div.field
      [:div.fields
       [:div.eight.wide.field
        [:label "Answer Value"]
        [LabelValueDropdown context label-id (first values)
         (fn [new-value]
           (update-filter #(assoc % :values
                                  (if (nil? new-value) nil [new-value]))))]]
       [:div.eight.wide.field
        [:label "Inclusion"]
        [BooleanDropdown context inclusion
         (fn [new-value]
           (update-filter #(assoc % :inclusion new-value)))]]]]
     [:div.field
      [:div.fields
       [:div.eight.wide.field
        [:label "Confirmed"]
        [BooleanDropdown context confirmed
         (fn [new-value]
           (update-filter #(assoc % :confirmed new-value)))]]]]]))

(defmethod FilterEditorFields :consensus [context filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [status inclusion]} value]
    [:div.ui.small.form
     [:div.field
      [:div.fields
       [:div.eight.wide.field
        [:label "Consensus Status"]
        [ConsensusStatusDropdown context status
         (fn [new-value]
           (update-filter #(assoc % :status new-value)))]]
       [:div.eight.wide.field
        [:label "Inclusion"]
        [BooleanDropdown context inclusion
         (fn [new-value]
           (update-filter #(assoc % :inclusion new-value)))]]]]]))

(defn- FilterEditNegationControl [context filter-idx ifilter update-filter]
  (let [[[_ value]] (vec ifilter)
        {:keys [negate]} value]
    [:div.ui.small.form
     [:div.field.filter-negation>div.fields
      [:div.sixteen.wide.field
       [:div.ui.fluid.buttons
        [:button.ui.tiny.fluid.button
         {:class (if negate nil "primary")
          :on-click
          (util/wrap-user-event
           (fn [] (update-filter #(dissoc % :negate))))}
         "Match"]
        [:button.ui.tiny.fluid.button
         {:class (if negate "primary" nil)
          :on-click
          (util/wrap-user-event
           (fn [] (update-filter #(assoc % :negate true))))}
         "Exclude"]]]]]))

(defn- FilterEditElement
  [context filter-idx]
  (let [filters @(subscribe [::filters-input context])
        ifilter (nth filters filter-idx)
        update-filter #(dispatch [::update-filter-input context filter-idx %])]
    [:div.ui.secondary.segment.edit-filter
     [FilterEditNegationControl context filter-idx ifilter update-filter]
     [FilterEditorFields context filter-idx ifilter update-filter]
     [FilterEditButtons context filter-idx]]))

(defmulti filter-description (fn [context ftype value] ftype))

(defmethod filter-description :default [context ftype value]
  (str "unknown filter (" (pr-str ftype) ")"))

(defmethod filter-description :has-user [context ftype value]
  (let [{:keys [user content confirmed]} value
        confirmed-str (cond (= content :annotations)  nil
                            (true? confirmed)         "confirmed"
                            (false? confirmed)        "unconfirmed"
                            :else                     nil)
        content-str (case content
                      :labels "labels"
                      :annotations "annotations"
                      "content")
        user-name (when (integer? user)
                    @(subscribe [:user/display user]))
        user-str (if (integer? user)
                   (str "user " (pr-str user-name))
                   "any user")]
    (->> ["has" confirmed-str content-str "from" user-str]
         (remove nil?)
         (str/join " "))))

(defmethod filter-description :has-label [context ftype value]
  (let [{:keys [label-id users values inclusion confirmed]} value
        confirmed-str (cond (true? confirmed)  "confirmed"
                            (false? confirmed) "unconfirmed"
                            :else              nil)
        label-str (if (nil? label-id)
                    (->> ["any" confirmed-str "label"]
                         (remove nil?)
                         (str/join " "))
                    (str (if (nil? confirmed-str)
                           "" (str confirmed-str " "))
                         "label ("
                         (pr-str @(subscribe [:label/display label-id]))
                         ")"))
        inclusion-str (case inclusion
                        true "with positive inclusion"
                        false "with negative inclusion"
                        nil)
        values-str (when (not-empty values)
                     (str
                      (if inclusion-str "and" "with")
                      " value ("
                      (->> values (map pr-str) (str/join " OR "))
                      ")"))
        users-str (if (empty? users)
                    "any user"
                    (str
                     "user ("
                     (->> users
                          (map #(pr-str @(subscribe [:user/display %])))
                          (str/join " OR "))
                     ")"))]
    (->> ["has" label-str
          inclusion-str values-str
          "from" users-str]
         (remove nil?)
         (str/join " "))))

(defmethod filter-description :consensus [context ftype value]
  (let [{:keys [status inclusion]} value
        status-str (-> (cond (= status :determined)
                             "Consistent OR Resolved"
                             (keyword? status)
                             (-> status name str/capitalize)
                             :else "Any")
                       (#(str "(" % ")")))
        inclusion-str (case inclusion
                        true "positive inclusion"
                        false "negative inclusion"
                        nil)]
    (cond-> (str "has consensus status " status-str)
      inclusion-str (str " for " inclusion-str))))

(defn- FilterDescribeElement
  [context filter-idx]
  [:div.describe-filter
   (if (nil? filter-idx)
     [:div.ui.tiny.fluid.button
      "all articles"]
     (let [{:keys [labels]} @(subscribe [:project/raw])
           filters @(subscribe [::filters-input context])
           fr (nth filters filter-idx)
           [[filter-type value]] (vec fr)
           label-name #(get-in labels [% :short-label])
           description (as-> (filter-description context filter-type value) s
                         (if (:negate value)
                           (str "NOT [" s "]")
                           s))]
       [:div.ui.center.aligned.middle.aligned.grid
        [:div.row
         [:div.middle.aligned.two.wide.column.delete-filter
          [:div.ui.tiny.fluid.icon.button
           {:on-click #(dispatch-sync [::delete-filter context filter-idx])}
           [:i.times.icon]]]
         [:div.middle.aligned.fourteen.wide.column.describe-filter
          [:button.ui.tiny.fluid.button
           {:on-click #(dispatch-sync [::edit-filter context filter-idx])}
           description]]]]))])

(defn- TextSearchDescribeElement [context]
  (let [text-search @(subscribe [::al/get context [:text-search]])]
    (when (and (string? text-search)
               (not-empty text-search))
      [:div.describe-filter
       [:div.ui.center.aligned.middle.aligned.grid
        [:div.row
         [:div.middle.aligned.two.wide.column.delete-filter
          [:div.ui.tiny.fluid.icon.button
           {:on-click #(dispatch-sync [::set-text-search context nil])}
           [:i.times.icon]]]
         [:div.middle.aligned.fourteen.wide.column
          [:div.ui.tiny.fluid.button
           {:on-click
            #(-> (js/$ "#article-search") (.focus))}
           (str "text contains "
                (->> (str/split text-search #"[ \t\r\n]+")
                     (map pr-str)
                     (str/join " AND ")))]]]]])))

(defn- NewFilterElement [context]
  (when (not @(subscribe [::editing-filter? context]))
    (let [items [[:has-user "User Content" "user"]
                 [:has-label "Labels" "tags"]
                 #_ [:has-annotation "Annotation" "quote left"]
                 [:consensus "Consensus" "users"]]
          touchscreen? @(subscribe [:touchscreen?])]
      [:div.ui.small.form
       [ui/selection-dropdown
        [:div.text "Add filter..."]
        (map-indexed
         (fn [i [ftype label icon]]
           [:div.item
            {:key [:new-filter-item i]
             :data-value (name ftype)}
            [:i {:class (str icon " icon")}]
            (str label)])
         items)
        {:class
         (if true #_ touchscreen?
             "ui fluid selection dropdown"
             "ui fluid search selection dropdown")
         :onChange
         (fn [v t]
           (when (not-empty v)
             (dispatch-sync [::add-filter context (keyword v)])))}]])))

(reg-event-fx
 :article-list/load-settings [trim-v]
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

(reg-event-fx
 :article-list/load-preset [trim-v]
 (fn [_ [context preset-name]]
   (let [preset (get (filter-presets) :self)]
     {:dispatch [:article-list/load-settings context preset]})))

(defn- WrapFilterDisabled [content enabled? message width]
  (if enabled?
    content
    (list
     ^{:key :content}
     [ui/with-tooltip [:div content]]
     ^{:key :tooltip}
     [:div.ui.inverted.popup.transition.hidden.inverted.filters-tooltip
      {:style {:min-width width}}
      message])))

(defn- FilterPresetsForm [context]
  (let [filters @(subscribe [::filters-input context])
        text-search @(subscribe [::al/get context [:text-search]])
        presets (filter-presets)
        make-button
        (fn [pkey text icon-class enabled?]
          (let [preset (get presets pkey)]
            [:button.ui.tiny.fluid.button
             {:class (cond-> ""
                       (and enabled? (= filters (:filters preset)))
                       (str " grey")
                       (not enabled?)
                       (str " disabled"))
              :on-click
              (when enabled?
                (util/wrap-user-event
                 #(dispatch-sync [:article-list/load-settings
                                  context (merge preset {:text-search text-search})])))}
             #_ [:i {:class (str icon-class " icon")}]
             text]))]
    [:div.ui.segment.filter-presets
     [:form.ui.small.form
      {:on-submit (util/wrap-prevent-default #(do nil))}
      [:div.sixteen.wide.field
       [:label "Presets"]
       [:div.ui.three.column.grid
        [:div.column
         (let [enabled? (and @(subscribe [:self/user-id])
                             @(subscribe [:self/member?]))]
           (WrapFilterDisabled
            [make-button :self "Self" "user" enabled?]
            enabled? "Not a member of this project" "15em"))]
        [:div.column
         [make-button :content "Labeled" "tags" true]]
        [:div.column
         [make-button :inclusion "Inclusion" "circle plus" true]]]]]]))

(defn- DisplayOptionsForm [context]
  (let [options @(subscribe [::al/display-options context])
        make-button
        (fn [key text icon-class]
          (let [enabled? (true? (get options key))]
            [:button.ui.tiny.fluid.icon.labeled.button.toggle
             {:on-click
              (util/wrap-user-event
               #(dispatch-sync [::al/set-display-option context key (not enabled?)]))}
             (if enabled?
               [:i.green.circle.icon]
               [:i.grey.circle.icon])
             #_ [:span [:i {:class (str icon-class " icon")}]]
             text]))]
    [:div.ui.segment.display-options
     [:div.ui.small.form
      [:div.sixteen.wide.field
       [:label "Display Options"]
       [:div.ui.two.column.grid
        [:div.column
         [make-button :self-only "Self Only" "user"]]
        [:div.column
         [make-button :show-inclusion "Inclusion" "circle plus"]]
        [:div.column
         [make-button :show-labels "Labels" "tags"]]
        [:div.column
         [make-button :show-notes "Notes" "pencil alternate"]]
        [:div.column
         [make-button :show-unconfirmed "Unconfirmed" nil]]]]]]))

(defn- SortOptionsForm [context]
  (let [sort-by @(subscribe [::al/sort-by context])
        sort-dir @(subscribe [::al/sort-dir context])]
    [:div.ui.segment.sort-options
     [:div.ui.small.form
      [:div.field
       [:label "Sort By"]
       [:div.fields
        [:div.nine.wide.field
         [SortByDropdown context sort-by
          #(dispatch [::al/set context [:sort-by] %])]]
        [:div.seven.wide.field
         [:div.ui.tiny.fluid.buttons
          [:button.ui.button
           {:class (when (= sort-dir :asc) "grey")
            :on-click #(do (dispatch-sync [::al/set context [:sort-dir] :asc])
                           (al/reload-list context :transition))}
           "Asc"]
          [:button.ui.button
           {:class (when (= sort-dir :desc) "grey")
            :on-click #(do (dispatch-sync [::al/set context [:sort-dir] :desc])
                           (al/reload-list context :transition))}
           "Desc"]]]]]]]))

(defn ResetReloadForm [context]
  (let [recent-nav-action @(subscribe [::al/get context [:recent-nav-action]])
        any-filters? (not-empty @(subscribe [::filters-input context]))
        display @(subscribe [::al/display-options (al/cached context)])
        can-reset? (or any-filters? (not= display (:display al/default-options)))]
    [:div.ui.small.form
     [:div.sixteen.wide.field
      [:div.ui.grid
       [:div.thirteen.wide.column
        [:button.ui.tiny.fluid.icon.labeled.button
         {:on-click (when can-reset?
                      #(do (dispatch [::al/reset-filters context])
                           (al/reload-list context :transition)
                           (dispatch [::reset-filters-input context])))
          :class (if can-reset? nil "disabled")}
         [:i.times.icon]
         "Reset Filters"]]
       [:div.three.wide.column
        [:button.ui.tiny.fluid.icon.button
         {:class (when (= recent-nav-action :refresh) "loading")
          :on-click (util/wrap-user-event
                     #(al/reload-list context :refresh))}
         [:i.repeat.icon]]]]]]))

(defn- ArticleListFiltersRow [context]
  (let [get-val #(deref (subscribe [::al/get context %]))
        recent-nav-action (get-val [:recent-nav-action])
        {:keys [expand-filters]
         :as display-options} @(subscribe [::al/display-options context])
        project-id @(subscribe [:active-project-id])
        {:keys [labels]} @(subscribe [:project/raw])
        filters @(subscribe [::al/filters context])
        loading? (= recent-nav-action :refresh)
        view-button
        (fn [option-key label]
          (let [status (get display-options option-key)]
            [:button.ui.small.labeled.icon.button
             {:on-click
              (util/wrap-user-event
               #(dispatch [::al/set-display-option
                           context option-key (not status)]))}
             (if status
               [:i.green.circle.icon]
               [:i.grey.circle.icon])
             label]))]
    [:div.ui.segments.article-filters.article-filters-row
     [:div.ui.secondary.middle.aligned.grid.segment.filters-minimal
      [:div.row
       [:div.one.wide.column.medium-weight.filters-header
        "Filters"]
       [:div.nine.wide.column.filters-summary
        [:span
         #_
         (doall
          (for [filter-idx ...]
            [FilterDescribeElement context filter-idx]))]]
       [:div.six.wide.column.right.aligned.control-buttons
        [:button.ui.small.icon.button
         {:class (if (and loading? (= recent-nav-action :refresh))
                   "loading" "")
          :on-click (util/wrap-user-event
                     #(al/reload-list context :refresh))}
         [:i.repeat.icon]]
        [:button.ui.small.icon.button
         {:on-click (util/wrap-user-event
                     #(do (dispatch [::al/reset-filters context])
                          (al/reload-list context :transition)))}
         [:i.times.icon]]]]]
     [:div.ui.secondary.segment.no-padding
      [:button.ui.tiny.fluid.icon.button
       {:style (cond-> {:border-top-left-radius "0"
                        :border-top-right-radius "0"}
                 expand-filters
                 (merge {:border-bottom-left-radius "0"
                         :border-bottom-right-radius "0"}))
        :on-click (util/wrap-user-event
                   #(dispatch [::al/set-display-option
                               context :expand-filters (not expand-filters)]))}
       (if expand-filters
         [:i.chevron.up.icon]
         [:i.chevron.down.icon])]]
     (when expand-filters
       [:div.ui.secondary.segment.filters-content
        [:div.ui.small.form
         [:div.field
          [:div.fields
           [:div.four.wide.field
            [:label "Text search"]
            [TextSearchInput context]]
           [:div.six.wide.field
            [:label "View Options"]
            [view-button :show-inclusion "Inclusion"]
            [view-button :show-labels "Labels"]
            [view-button :show-notes "Notes"]]
           [:div.six.wide.field]]]]])]))

(defn- FilterColumnCollapsed [context]
  [:div.ui.segments.article-filters.article-filters-column.collapsed
   {:on-click
    (util/wrap-user-event
     #(do (dispatch-sync [::al/set-display-option
                          context :expand-filters true])
          #_ (dispatch-sync [::al/set-active-article context nil])))}
   [:div.ui.center.aligned.header.segment
    "Filters"]
   [:div.ui.one.column.center.aligned.grid.segment.expand-filters
    [:div.column
     [:i.fitted.angle.double.right.icon]]]])

(defn- FilterColumnElement [context]
  (let [active-filters @(subscribe [::al/filters context])
        input-filters @(subscribe [::filters-input context])
        recent-nav-action @(subscribe [::al/get context [:recent-nav-action]])]
    [:div.ui.segments.article-filters.article-filters-column.expanded
     [:a.ui.center.aligned.header.grid.segment.collapse-header
      {:on-click
       (util/wrap-user-event
        #(dispatch-sync [::al/set-display-option
                         context :expand-filters false]))}
      [:div.two.wide.column.left.aligned
       [:i.fitted.angle.double.left.icon]]
      [:div.twelve.wide.column
       "Filters"]
      [:div.two.wide.column.right.aligned
       [:i.fitted.angle.double.left.icon]]]
     [FilterPresetsForm context]
     [DisplayOptionsForm context]
     [SortOptionsForm context]
     [:div.ui.segment.filters-content
      [:div.inner
       [ResetReloadForm context]
       [TextSearchDescribeElement context]
       (if (empty? input-filters)
         [FilterDescribeElement context nil]
         (doall
          (map-indexed
           (fn [filter-idx fr] ^{:key [:filter-element filter-idx]}
             (if (in? active-filters fr)
               ^{:key [:filter-text filter-idx]}
               [FilterDescribeElement context filter-idx]
               ^{:key [:filter-editor filter-idx]}
               [FilterEditElement context filter-idx]))
           input-filters)))
       [NewFilterElement context]]]]))

(defn- ArticleListFiltersColumn [context expanded?]
  [:div
   (if expanded?
     [FilterColumnElement context]
     [FilterColumnCollapsed context])])
