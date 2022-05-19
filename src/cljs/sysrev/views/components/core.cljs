(ns sysrev.views.components.core
  (:require ["clipboard" :as Clipboard]
            ["dropzone" :as Dropzone]
            ["jquery" :as $]
            ["@material-ui/core" :as mui]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [medley.core :as medley]
            [reagent.core :as r]
            [reagent.dom :as rdom :refer [dom-node]]
            [reagent.ratom :as ratom]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx]]
            [sysrev.util :as util :refer [in? css nbsp wrap-user-event]
             :refer-macros [assert-single]]
            [sysrev.views.semantic :as S :refer
             [Message Button Radio Checkbox Popup]]))

(defn dangerous
  "Produces a react component using dangerouslySetInnerHTML
   Ex: (dangerous :div (:abstract record))"
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

(defn SelectionDropdown [options &
                         [{:keys [id class onChange selection fluid value extra]
                           :or {selection true}}]]
  [S/Dropdown (cond-> {:id id :class class
                       :selection selection :fluid fluid
                       :options options
                       :on-change onChange
                       :icon "dropdown"}
                value (merge {:value value})
                extra (merge extra))])

(s/def ::tab-id keyword?)
(s/def ::content any?)
(s/def ::action (or string? fn?))
(s/def ::class string?)
(s/def ::menu-tab
  (s/keys :req-un [::tab-id ::content ::action]
          :opt-un [::class]))

(defn Tooltip [{:keys [tooltip trigger
                       hoverable position transition
                       mouse-enter-delay mouse-leave-delay
                       variation basic flowing
                       class style]
                :or {hoverable false
                     position "top center"
                     mouse-enter-delay 400
                     mouse-leave-delay 0
                     transition "fade up"}}]
  (assert (some? tooltip) "Tooltip missing value for `tooltip`")
  (assert (some? trigger) "Tooltip missing value for `trigger`")
  [Popup (cond-> {:class (css "sysrev-tooltip" class)
                  :content (r/as-element tooltip)
                  :trigger (r/as-element trigger)}
           ;; (some? inline)             (assoc :inline inline)
           (some? hoverable)          (assoc :hoverable hoverable)
           (some? position)           (assoc :position position)
           (some? transition)         (assoc :transition transition)
           (some? mouse-enter-delay)  (assoc :mouse-enter-delay mouse-enter-delay)
           (some? mouse-leave-delay)  (assoc :mouse-leave-delay mouse-leave-delay)
           (some? variation)          (assoc :variation variation)
           (some? basic)              (assoc :basic basic)
           (some? flowing)            (assoc :flowing flowing)
           (some? style)              (assoc :style style))])

(defn PrimaryTabbedMenu [left-entries right-entries active-tab-id
                         & [menu-class mobile?]]
  (let [menu-class (or menu-class "")
        left-entries (remove nil? left-entries)
        right-entries (remove nil? right-entries)
        render-entry
        (fn [{:keys [tab-id action content class disabled tooltip] :as entry}]
          (let [active? (= tab-id active-tab-id)
                item (when entry
                       [:a {:key tab-id
                            :class (css [active? "active"]
                                        "item"
                                        [class class]
                                        [disabled "disabled"])
                            :href (when (string? action) action)
                            :on-click (when-not (string? action)
                                        (some-> action (wrap-user-event)))}
                        content])]
            (if (and disabled tooltip)
              [S/Popup {:key tab-id :class "filters-tooltip"
                        :hoverable true :inverted true
                        :trigger (r/as-element item)
                        :content (r/as-element [:div {:style {:min-width "10em"}}
                                                tooltip])} ]
              item)))]
    [:div.ui.secondary.pointing.menu.primary-menu
     {:class (css menu-class [mobile? "tiny"])}
     (doall (for [entry left-entries] ^{:key entry}
              [render-entry entry]))
     (when (seq right-entries)
       [:div.right.menu
        (doall (for [entry right-entries] ^{:key entry}
                 [render-entry entry]))])]))

(defn SecondaryTabbedMenu [left-entries right-entries active-tab-id
                           & [menu-class mobile?]]
  (let [menu-class (or menu-class "")
        render-entry (fn [{:keys [tab-id action content class] :as entry}]
                       (when entry
                         [:a {:key tab-id
                              :class (css [(= tab-id active-tab-id) "active"]
                                          "item" class)
                              :href (when (string? action) action)
                              :on-click (when-not (string? action)
                                          (some-> action (wrap-user-event)))}
                          content]))]
    [:div.ui.secondary.pointing.menu.secondary-menu {:class menu-class}
     (doall (for [entry (remove empty? left-entries)]
              ^{:key (:tab-id entry)}
              [render-entry entry]))
     (when (seq right-entries)
       (if mobile?
         [:div.right.menu
          [S/Dropdown {:class "item", :simple true, :size "small", :direction "left"
                       :label "More", :icon "down chevron"
                       :options right-entries}]]
         [:div.right.menu
          (doall (for [entry (remove empty? right-entries)]
                   ^{:key (:tab-id entry)}
                   [render-entry entry]))]))]))

(defn tabbed-panel-menu [entries active-tab-id & [menu-class _mobile?]]
  (let [menu-class (or menu-class "")
        entries (remove nil? entries)]
    [:div.tabbed-panel
     [:div {:class (css "ui" (util/num-to-english (count entries))
                        "item tabbed menu tabbed-panel" menu-class)}
      (doall (for [{:keys [tab-id action content class disabled]} entries]
               [:a {:key tab-id
                    :class (css [(= tab-id active-tab-id) "active"] "item"
                                [class class] [disabled "disabled"]
                                (str "tab-" (name tab-id)))
                    :href (when (string? action) action)
                    :on-click (when-not (string? action)
                                (some-> action (wrap-user-event)))}
                content]))]]))

(defn UrlLink
  "Renders a link with human-formatted text based on href value."
  [href & [props]]
  [:a (merge props {:href href})
   (util/humanize-url href)])

(defn OutLink [url & [title]]
  [:div.item>a {:target "_blank" :href url}
   (or title (util/url-domain url)) nbsp [:i.external.icon]])

(defn UpdatedTimeLabel [dt & [shorten?]]
  [:div.ui.tiny.label.updated-time {:title (util/date-format dt "MMM, do yyyy hh:mm a")}
   ((if shorten? util/time-elapsed-string-short util/time-elapsed-string)
    dt)])

(defn ThreeStateSelection
  "props are:
  {:set-answer! <fn>     ; sets the label's answer to argument of fn
   :value       <r/atom> ; atom resolves to single value boolean or nil
  }"
  [{:keys [set-answer! value]}]
  ;; nil for unset, true, false
  (let [domid (util/random-id)]
    (fn [{:keys [set-answer! value]}]
      (let [size (if (util/full-size?) "large" "small")
            class (str "ui " size " buttons three-state")
            bclass (fn [secondary? selected?]
                     (str "ui " size " "
                          (cond (not selected?) ""
                                secondary?        "grey"
                                :else             "primary")
                          " icon button"))
            get-domid #(str domid "_" (pr-str %))
            set-focus #(-> (fn [] (.focus ($ (str "#" (get-domid %)))))
                           (js/setTimeout 25))
            set-value-focus #(do (set-answer! %) (set-focus %))
            render
            (fn [bvalue]
              (let [curval @value]
                [:div
                 (cond->
                     {:id (get-domid bvalue)
                      :class (bclass (nil? bvalue) (= curval bvalue))
                      :on-click (wrap-user-event #(set-value-focus bvalue))
                      :data-value (pr-str bvalue)
                      :on-key-down
                      (when (= curval bvalue)
                        #(cond (->> % .-key (in? ["Backspace" "Delete" "Del"]))
                               (set-value-focus nil)
                               (and (->> % .-key (= "ArrowLeft"))
                                    (= bvalue nil))
                               (set-value-focus false)
                               (and (->> % .-key (= "ArrowLeft"))
                                    (= bvalue true))
                               (set-value-focus nil)
                               (and (->> % .-key (= "ArrowRight"))
                                    (= bvalue false))
                               (set-value-focus nil)
                               (and (->> % .-key (= "ArrowRight"))
                                    (= bvalue nil))
                               (set-value-focus true)
                               :else true))}
                   (= curval bvalue) (merge {:tabIndex "0"}))
                 (case bvalue false "No", nil "?", true "Yes")]))]
        [:div {:class class}
         [render false]
         [render nil]
         [render true]]))))

(defn ThreeStateSelectionIcons
  [on-change curval &
   {:keys [icons] :or {icons {false [:i.minus.circle.icon]
                              nil   [:i.question.circle.outline.icon]
                              true  [:i.plus.circle.icon]}}}]
  ;; nil for unset, true, false
  (let [size (if (util/full-size?) "large" "small")
        class (str "ui " size " fluid buttons three-state-icon")
        bclass (fn [secondary? selected?]
                 (str "ui " size " "
                      (cond (not selected?) ""
                            secondary?        "grey"
                            :else             "black")
                      " icon button"))]
    [:div {:class class}
     [:div.ui {:class (bclass false (false? curval))
               :on-click (wrap-user-event #(on-change false))}
      (get icons false)]
     [:div.ui {:class (bclass true (nil? curval))
               :on-click (wrap-user-event #(on-change nil))}
      (get icons nil)]
     [:div.ui {:class (bclass false (true? curval))
               :on-click (wrap-user-event #(on-change true))}
      (get icons true)]]))

(defn UiHelpIcon [& {:keys [size class style] :or {size ""}}]
  [:i.circle.question.mark.icon {:class (css "grey" size class "noselect")
                                 :style (merge {:margin "0 4px"} style)}])

(defn UiHelpTooltip [element & {:keys [help-content help-element options]}]
  (assert-single help-content help-element)
  [Tooltip (merge {:hoverable false
                   :position "top left"
                   :trigger element
                   :mouse-enter-delay 300
                   :mouse-leave-delay 0
                   :tooltip (if help-content
                              [:div (doall (map-indexed #(if (string? %2)
                                                           ^{:key %1} [:p %2]
                                                           ^{:key %1} [:div %2])
                                                        help-content))]
                              help-element)}
                  options)])

(defn NoteContentLabel [content]
  (when (some-> content str/trim not-empty)
    [:div.ui.tiny.labeled.button.user-note
     [:div.ui.grey.button "Notes"]
     [:div.ui.basic.label content]]))

(defn ClipboardButton [target _child]
  (let [clip (atom nil)
        status (r/atom nil)
        transtime 1500
        default-class "ui primary button"
        success-class "ui green button"
        success-el [:span "Copied " [:i.circle.check.icon]]]
    (letfn [(reset-ui [] (reset! status nil))
            (clip-success [_]
              (reset! status true)
              (-> js/window
                  (.setTimeout reset-ui transtime)))
            (get-clipboard [el]
              (let [clip (Clipboard. (dom-node el))]
                (.on clip "success" clip-success)
                clip))
            (reset-clip! [el] (reset! clip (get-clipboard el)))
            (component-did-mount [this] (reset-clip! this))
            (component-did-update [this _] (reset-clip! this))
            (component-will-unmount []
              (when-not (nil? @clip)
                (.destroy @clip)
                (reset! clip nil)))
            (render [target child]
              [:div {:class (if @status success-class default-class)
                     :data-clipboard-target target}
               (if @status success-el child)])]
      (r/create-class
       {:display-name (str "clipboard-from-" target)
        :component-did-update component-did-update
        :component-did-mount component-did-mount
        :component-will-unmount component-will-unmount
        :reagent-render render}))))

(defn CenteredColumn
  "Renders a grid column that will take up the full height of its row and
  vertically center its content within the column and row.

  This is done by wrapping the column content in a nested grid with CSS styles
  applied to several components."
  [content class]
  [:div {:class (css class "vertical-column")}
   [:div.ui.middle.aligned.grid>div.row>div.middle.aligned.column
    [:div.vertical-column-content content]]])

(defn TopAlignedColumn
  "Renders a grid column that will take up the full height of its row and
  vertically align its content to the top of the element.

  This is done by wrapping the column content in a nested grid with CSS styles
  applied to several components."
  [content class]
  [:div {:class (css class "vertical-column")}
   [:div.ui.top.aligned.grid>div.row>div.top.aligned.column
    [:div.vertical-column-content.top content]]])

(defn FormLabelInfo
  "Renders label for a form field, optionally with a tooltip and
  informational tags (optional) attached."
  [label & {:keys [tooltip optional] :or {optional false}}]
  (let [label-with-tooltip (if (nil? tooltip)
                             label
                             [UiHelpTooltip [:span {:style {:width "100%"}}
                                             label [UiHelpIcon]]
                              :help-content tooltip
                              :options {:mouse-enter-delay 500}])]
    (if-not optional
      [:label label-with-tooltip]
      [:label [:div.ui.middle.aligned.grid
               [:div.ten.wide.left.aligned.column label-with-tooltip]
               [:div.six.wide.right.aligned.column
                [:div.ui.small.basic.label "Optional"]]]])))

(defn TextInput
  "Props:
  {:value         <reagent atom> ; value, optional
   :on-change     <fn>           ; a fn of event
   :on-mouse-up   <fn>           ; a fn of event, optional
   :on-mouse-down <fn>           ; a fn of event, optional
   :placeholder   <string>       ; optional
   :default-value <string>       ; optional
   :autofocus     <boolean>      ; should this start focused?
   :disabled      <boolean>      ; set disabled state on input
   :read-only     <boolean>      ; set readOnly attribute on input
  }"
  [{:keys [value on-change on-mouse-up on-mouse-down
           placeholder default-value autofocus disabled read-only]}]
  [:input.ui.input
   (cond-> {:type "text"
            :on-change on-change
            :class (css [disabled "disabled"])}
     (not (nil? default-value)) (assoc :default-value default-value)
     (and (nil? default-value) (not (nil? value)))
     #__ (assoc :value (if (#{cljs.core/Atom
                              reagent.ratom/RAtom
                              reagent.ratom/RCursor
                              reagent.ratom/Reaction}
                            (type value))
                         @value
                         value))
     (not (nil? placeholder)) (assoc :placeholder placeholder)
     (not (nil? on-mouse-up)) (assoc :on-mouse-up on-mouse-up)
     (not (nil? on-mouse-down)) (assoc :on-mouse-down on-mouse-down)
     autofocus (assoc :autoFocus true)
     disabled (assoc :disabled true)
     read-only (assoc :readOnly true))])

(defn TextInputField
  "Props:
  {:error         <string>       ; error message, optional
   :value         <reagent atom> ; value, optional
   :on-change     <fn>           ; a fn of event
   :on-mouse-up   <fn>           ; a fn of event, optional
   :on-mouse-down <fn>           ; a fn of event, optional
   :placeholder   <string>       ; optional
   :default-value <string>       ; optional
   :label         <string>       ; label value
   :autofocus     <boolean>      ; should this start focused?
   :disabled      <boolean>      ; set disabled state on input
   :read-only     <boolean>      ; set readOnly attribute on input
   :tooltip       <sequence>     ; tooltip strings, optional
   :optional      <boolean>      ; indicate value is not required
  }"
  [{:keys [error value on-change on-mouse-up on-mouse-down
           placeholder default-value label autofocus disabled read-only
           field-class tooltip optional]}]
  [:div.field {:class (css field-class [error "error"])}
   [FormLabelInfo label :tooltip tooltip :optional optional]
   [:input.ui.input
    (cond-> {:type "text"
             :on-change on-change
             :class (css [disabled "disabled"])}
      (not (nil? default-value)) (assoc :default-value default-value)
      (and (nil? default-value) (not (nil? value)))
      #__ (assoc :value (if (#{cljs.core/Atom
                               reagent.ratom/RAtom
                               reagent.ratom/RCursor
                               reagent.ratom/Reaction}
                             (type value))
                          @value
                          value))
      (not (nil? placeholder)) (assoc :placeholder placeholder)
      (not (nil? on-mouse-up)) (assoc :on-mouse-up on-mouse-up)
      (not (nil? on-mouse-down)) (assoc :on-mouse-down on-mouse-down)
      autofocus (assoc :autoFocus true)
      disabled (assoc :disabled true)
      read-only (assoc :readOnly true))]
   (when error
     [:div.ui.red.message error])])

(defn LabeledCheckbox
  "Checkbox input element with label."
  [{:keys [checked? disabled label on-change]}]
  [:div.ui.checkbox {:class (css [disabled "disabled"])
                     :style {:margin-right "0.5em"}}
   [:input {:type "checkbox"
            :on-change (wrap-user-event on-change :timeout false)
            :checked (boolean checked?)
            :disabled (boolean disabled)}]
   [:label label]])

(defn LabeledCheckboxField
  "Form field with labeled checkbox and optional tooltip."
  [{:keys [checked? disabled error field-class label on-change optional tooltip]}]
  [:div.field {:key [:label label]
               :class (css field-class [error "error"])}
   [:div.ui.checkbox {:style {:width "100%"} ;; need width 100% to fit tooltip
                      :class (css [disabled "disabled"])}
    [:input {:type "checkbox"
             :on-change (wrap-user-event on-change :timeout false)
             :checked (boolean checked?)
             :disabled (boolean disabled)}]
    [FormLabelInfo label :tooltip tooltip :optional optional]]
   (when error
     [:div.ui.red.message error])])

(defn SaveCancelForm [& {:keys [can-save? can-reset? on-save on-reset saving? id]}]
  [:div.ui.two.column.grid.save-reset-form
   [:div.column.save
    [:button.ui.fluid.right.labeled.positive.icon.button.save-changes
     {:id id
      :class (css [(not can-save?) "disabled"]
                  [saving? "loading"])
      :on-click (wrap-user-event #(when (and can-save? on-save (not saving?)) (on-save)))}
     "Save Changes"
     [:i.check.circle.outline.icon]]]
   [:div.column.reset
    [:button.ui.fluid.right.labeled.icon.button.cancel-changes
     {:class (css [(not can-reset?) "disabled"])
      :on-click (wrap-user-event #(when (and can-reset? on-reset) (on-reset)))}
     "Cancel"
     [:i.times.icon]]]])

(defn ConfirmationDialog
  "A confirmation dialog for confirming or cancelling an action.
  Arguments:
  {
  :on-cancel            fn  ; user clicks cancel, same fn used for dismissing
                            ; alert
  :on-confirm           fn  ; user clicks confirm
  :title            string  ; title text for message box
  :message          string  ; content text for message box (optional)
  :action-color     string  ; css color class to represent confirm action
  }"
  [{:keys [on-cancel on-confirm title message action-color]
    :or {action-color "orange"}}]
  [:div.confirm-cancel-form
   [:div.ui.icon.warning.message.confirm-warning
    [:i.warning.icon {:class action-color}]
    [:div.content
     [:div.header title]
     (when message [:p.bold {:style {:font-size "16px"}} message])]]
   [:div.ui.two.column.grid.confirm-cancel-form
    [:div.column>button.ui.fluid.button.confirm-cancel-form-confirm
     {:on-click (wrap-user-event on-confirm) :class action-color}
     "Confirm"]
    [:div.column>button.ui.fluid.button.confirm-cancel-form-cancel
     {:on-click (wrap-user-event on-cancel)}
     "Cancel"]]])

(defn UploadContainer
  "Create uploader form component."
  [_childer upload-url on-success & _args]
  (let [id (util/random-id)
        csrf-token (subscribe [:csrf-token])
        opts {:url upload-url
              :headers (when-let [token @csrf-token] {"x-csrf-token" token})
              :maxFilesize (* 1000 10)
              :timeout (* 1000 60 60 4)}
        error-msg (r/atom nil)]
    (letfn [(init-dropzone []
              (-> (Dropzone/Dropzone.
                   (str "#" id)
                   (clj->js
                    (->> {:previewTemplate
                          (-> js/document
                              (.querySelector (str "#" id "-template"))
                              .-innerHTML)
                          :previewsContainer (str "#" id "-preview")
                          :clickable (str "#" id "-button")}
                         (merge opts))))
                  (.on "error" (fn [file msg _]
                                 (util/log-warn "Upload error [%s]: $s" file msg)
                                 (reset! error-msg (-> (re-find #"message\",\"(.*)?\"" msg)
                                                       (nth 1)))
                                 true))
                  (.on "success" on-success)))]
      (r/create-class
       {:reagent-render
        (fn [childer upload-url _on-success text class style {:keys [post-error-text]}]
          [:div [childer id upload-url error-msg text class style]
           (when @error-msg
             [:div {:style {:text-align "center" :margin-top "1em"}}
              [:i.ui.red.exclamation.icon]
              [:span @error-msg] [:br]
              [:span post-error-text]])])
        :component-did-mount #(init-dropzone)
        :display-name "upload-container"}))))

(defn- UploadButtonImpl [id & [_upload-url _error-msg text class style]]
  [:div
   [:div.dropzone {:id id}
    [:button.ui.button.upload-button {:id (str id "-button")
                                      :style (-> {:cursor "pointer"} (merge style))
                                      :class (css [(util/mobile?)          "tiny"
                                                   (not (util/full-size?)) "small"]
                                                  class)}
     [:i.green.add.circle.icon] text]
    [:div.dropzone-previews {:id (str id "-preview")}]]
   [:div {:style {:display "none"}}
    [:div.dz-preview.dz-file-preview {:id (str id "-template")}
     [:div.ui.center.aligned.segment {:style {:margin-top "1em"}}
      [:div.dz-details
       [:div.dz-filename [:span {:data-dz-name ""}]]
       [:div.dz-size {:data-dz-size ""}]
       #_ [:img {:data-dz-thumbnail ""}]]
      [:div.ui.progress.dz-progress {:id (str id "-progress")}
       [:div.bar {:id (str id "-progress-bar")
                  :data-dz-uploadprogress ""}]
       [:div.label {:id (str id "-progress-label")}]]
      #_ [:div.dz-progress
          [:span.dz-upload {:data-dz-uploadprogress ""}]]
      ;[:div.dz-error-message
      ; [:span {:data-dz-errormessage ""}]]
      ]]]])

(defn UploadButton [upload-url on-success text & [class style & {:keys [post-error-text]}]]
  [UploadContainer UploadButtonImpl upload-url on-success text
   class style {:post-error-text post-error-text}])

(defn CursorMessage [cursor & [props]]
  (when (seq @cursor)
    [Message (merge props {:on-dismiss #(reset! cursor nil)})
     (str @cursor)]))

(defn- RadioCheckboxButton [{:keys [active? on-click text title type]}]
  (let [dark? @(subscribe [:self/dark-theme?])]
    [Button (cond-> {:class (css "button-radio"
                                 [(= type "checkbox") "button-checkbox"])
                     :size "mini"
                     :primary active?
                     :secondary (and (not active?) dark?)
                     :on-click on-click}
              title (assoc :title title))
     [(case (or type "radio")
        "radio"    Radio
        "checkbox" Checkbox) {:checked active?}]
     [:span text]]))

(defn RadioButton [{:keys [active? on-click text title] :as options}]
  [RadioCheckboxButton (merge options {:type "radio"})])

(defn CheckboxButton [{:keys [active? on-click text title] :as options}]
  [RadioCheckboxButton (merge options {:type "checkbox"})])

(defn Popper [{:keys [anchor-component props]} _child]
  (let [anchor-node (r/atom nil)]
    (r/create-class
     {:display-name "Popper"
      :component-did-mount
      (fn []
        (reset! anchor-node (dom-node anchor-component)))
      :component-did-update
      (fn []
        (reset! anchor-node (dom-node anchor-component)))
      :reagent-render
      (fn [{:keys [anchor-component props]} child]
        (when-let [anchorEl @anchor-node]
          [:> mui/Popper (assoc props :anchorEl anchorEl)
           (r/as-element
            child)]))})))

(defn WrapClickOutside [{:keys [handle-click-outside]} _child]
  (let [dom-node (atom nil)
        handler #(let [node @dom-node]
                   (when (not (and node (.contains node (.-target %))))
                     (handle-click-outside %)))]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when handle-click-outside
          (reset! dom-node (rdom/dom-node this))
          (js/document.addEventListener "click" handler)))
      :component-will-unmount
      (when handle-click-outside
        (reset! dom-node nil)
        (js/document.removeEventListener "click" handler))
      :reagent-render
      (fn [_ child]
        child)})))

(defn MultiSelect [{:keys [cursor label on-change options]}]
  (let [on (when cursor @cursor)
        make-button
        (fn [key text]
          [:button.ui.tiny.fluid.icon.labeled.button.toggle
           {:on-click
            (when cursor
              (util/wrap-user-event
               (fn []
                 (-> (if (get on key)
                       (swap! cursor disj key)
                       (swap! cursor #(conj (or % #{}) key)))
                     ((or on-change identity))))))}
           (if (get on key)
             [:i.green.circle.icon]
             [:i.grey.circle.icon])
           text])]
    [:div.ui.segments>div.ui.segment.multiselect
     [:div.ui.small.form
      [:div.sixteen.wide.field
       [:label label]
       [:div.ui.two.column.grid
        (for [[key label] options]
          (let [name (-> (str/replace label " " "_")
                         (str/lower-case))]
            ^{:key (pr-str key)}
            [:div.column {:class (str "label_" name)}
             [make-button key label]]))]]]]))


(reg-sub ::alerts #(get-in % [:state :alerts]))

(reg-sub :current-alert
         :<- [::alerts]
         first)

(defn current-alert-id [db]
  (:id (first (get-in db [:state :alerts]))))

(def alert-message-timeout 3000)
(def alert-message-transition 250)

;; show an alert message (add to queue)
(reg-event-fx :alert
              (fn [{:keys [db]} [_ {:as message}]]
                (let [alert-id (util/random-id)]
                  (cond-> {:db (update-in db [:state :alerts]
                                          #(conj (into [] %) {:id alert-id
                                                              :message message
                                                              :visible true}))}
                    (nil? (current-alert-id db))
                    (assoc :dispatch-later
                           [{:ms (- alert-message-timeout
                                    alert-message-transition
                                    25)
                             :dispatch [::hide-alert alert-id]}
                            {:ms alert-message-timeout
                             :dispatch [::next-alert alert-id]}])))))

;; delete current alert message, shift to next in queue
(reg-event-fx ::next-alert
              (fn [{:keys [db]} [_ current-id]]
                (when (= current-id (current-alert-id db))
                  (let [next-db (update-in db [:state :alerts] #(into [] (rest %)))
                        next-id (current-alert-id next-db)]
                    (cond-> {:db next-db}
                      next-id (assoc :dispatch-later
                                     [{:ms (- alert-message-timeout
                                              alert-message-transition
                                              25)
                                       :dispatch [::hide-alert next-id]}
                                      {:ms alert-message-timeout
                                       :dispatch [::next-alert next-id]}]))))))

;; trigger hide animation for current alert
(reg-event-db ::hide-alert
              (fn [db [_ alert-id]]
                (if (= alert-id (current-alert-id db))
                  (update-in db [:state :alerts]
                             #(into [] (concat [(-> (first %) (assoc :visible false))]
                                               (rest %))))
                  db)))

(defn AlertMessageContainer [& [{:keys [opts-message opts-portal]}]]
  (let [{:keys [message visible]} @(subscribe [:current-alert])]
    [S/Transition {:visible (boolean visible)
                   :duration alert-message-transition}
     (let [{:keys [content header class style opts]} message]
       (if (nil? content)
         [:div]
         [S/Message (merge opts-message opts
                           {:class (css "alert-message" class)
                            :style (merge style {:position "fixed"
                                                 :top 0 :right 0
                                                 :margin-top 10 :margin-right 10
                                                 :z-index 1000})})
          (when header [S/MessageHeader header])
          content]))]))
