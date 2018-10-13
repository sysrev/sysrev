(ns sysrev.views.components
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [cljsjs.clipboard]
            [cljsjs.dropzone]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in?]]))

(defn dangerous
  "Produces a react component using dangerouslySetInnerHTML
   Ex: (dangerous :div (:abstract record))"
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

(defn labeled-input [label-text input-elt & [attrs label-attrs]]
  (let [attrs (or attrs {})
        label-attrs (or label-attrs {})]
    [:div.ui.labeled.input attrs
     [:div.ui.label label-attrs label-text]
     input-elt]))

(defn wrap-dropdown [elt & [{:keys [onChange] :as options}]]
  (r/create-class
   {:component-did-mount
    #(-> (js/$ (r/dom-node %))
         (.dropdown
          (clj->js
           (cond-> {}
             onChange (merge {:onChange onChange})))))
    :reagent-render (fn [elt] elt)}))

(defn selection-dropdown [selected-item items &
                          [{:keys [id class onChange]
                            :or {class "ui selection dropdown"}
                            :as options}]]
  [wrap-dropdown
   [:div {:id id :class class}
    [:i.dropdown.icon]
    selected-item
    (into [:div.menu] items)]
   options])

(defn dropdown-menu [entries & {:keys [icon-class dropdown-class label style]
                                :or {icon-class "small down chevron"
                                     dropdown-class "dropdown"
                                     label ""
                                     style {}}}]
  [wrap-dropdown
   [:div.ui {:class dropdown-class :style style}
    label
    [:i {:class (str icon-class " icon")
         :style (when-not (and (seqable? label)
                               (empty? label))
                  {:margin-left "0.7em"
                   :margin-right "0em"})}]
    [:div.menu
     (doall
      (for [{:keys [action content] :as entry} entries]
        (when entry
          ^{:key entry}
          [:a.item
           {:href (when (string? action) action)
            :on-click
            (util/wrap-user-event
             (cond (and (seq? action)
                        (= (count action) 2))
                   #(dispatch [:navigate
                               (first action) (second action)])

                   (vector? action)
                   #(dispatch [:navigate action])

                   (string? action) nil

                   :else action))}
           content])))]]])

(s/def ::tab-id keyword?)
(s/def ::content any?)
(s/def ::action (or string? fn?))
(s/def ::class string?)
(s/def ::menu-tab
  (s/keys :req-un [::tab-id ::content ::action]
          :opt-un [::class]))

(defn with-tooltip [content & [popup-options]]
  (r/create-class
   {:component-did-mount
    #(.popup (js/$ (r/dom-node %))
             (clj->js
              (merge
               {:inline true
                :hoverable true
                :position "top center"
                :delay {:show 400
                        :hide 0}
                :transition "fade up"}
               (or popup-options {}))))
    :reagent-render
    (fn [content] content)}))

(defn WrapMenuItemTooltip
  [content message tab-id & {:keys [width] :or {width "10em"}}]
  (list
   ^{:key [tab-id :content]}
   [with-tooltip [:div content]]
   ^{:key [tab-id :tooltip]}
   [:div.ui.inverted.popup.transition.hidden.inverted.filters-tooltip
    {:style {:min-width width}}
    message]))

(defn primary-tabbed-menu
  [left-entries right-entries active-tab-id & [menu-class mobile?]]
  (let [menu-class (or menu-class "")
        left-entries (remove nil? left-entries)
        right-entries (remove nil? right-entries)
        ;; n-tabs (count entries)
        ;; n-tabs-word (sutil/num-to-english n-tabs)
        render-entry
        (fn [{:keys [tab-id action content class disabled tooltip] :as entry}]
          (let [active? (= tab-id active-tab-id)
                item
                (when entry
                  [:a {:key tab-id
                       :class (cond-> ""
                                active?  (str " active")
                                true     (str " item")
                                class    (str " " class)
                                disabled (str " disabled"))
                       :href (when (string? action) action)
                       :on-click
                       (util/wrap-user-event
                        (cond (and (seq? action)
                                   (= (count action) 2))
                              #(dispatch [:navigate
                                          (first action) (second action)])

                              (vector? action)
                              #(dispatch [:navigate action])

                              (string? action) nil

                              :else action))}
                   content])]
            (if (and disabled tooltip)
              (WrapMenuItemTooltip item tooltip tab-id)
              (list item))))]
    [:div.ui.secondary.pointing.menu.primary-menu
     {:class (str menu-class " " (if mobile? "tiny" ""))}
     (doall
      (for [entry left-entries]
        (render-entry entry)))
     (when-not (empty? right-entries)
       (if (and false mobile?)
         [:div.right.menu
          [dropdown-menu right-entries
           :dropdown-class "dropdown item"
           :label "More"]]
         [:div.right.menu
          (doall
           (for [entry right-entries]
             (doall (render-entry entry))))]))]))
(s/fdef
 primary-tabbed-menu
 :args (s/cat :entries (s/coll-of ::menu-tab)
              :active-tab-id ::tab-id
              :menu-class (s/? string?)))

(defn secondary-tabbed-menu
  [left-entries right-entries active-tab-id & [menu-class mobile?]]
  (let [menu-class (or menu-class "")
        render-entry
        (fn [{:keys [tab-id action content class] :as entry}]
          (when entry
            [:a {:key tab-id
                 :class (str (if (= tab-id active-tab-id)
                               "active item" "item")
                             " " (if class class ""))
                 :href (when (string? action) action)
                 :on-click
                 (util/wrap-user-event
                  (cond (and (seq? action)
                             (= (count action) 2))
                        #(dispatch [:navigate
                                    (first action) (second action)])

                        (vector? action)
                        #(dispatch [:navigate action])

                        (string? action) nil

                        :else action))}
             content]))]
    [:div.ui
     {:class
      (str (if mobile? "" "")
           " secondary pointing menu secondary-menu "
           menu-class)}
     (doall
      (for [entry left-entries]
        (render-entry entry)))
     (when-not (empty? right-entries)
       (if mobile?
         [:div.right.menu
          [dropdown-menu right-entries
           :dropdown-class "dropdown item"
           :label "More"]]
         [:div.right.menu
          (doall
           (for [entry right-entries]
             (render-entry entry)))]))]))
(s/fdef
 secondary-tabbed-menu
 :args (s/cat :left-entries (s/coll-of ::menu-tab)
              :right-entries (s/coll-of ::menu-tab)
              :active-tab-id ::tab-id
              :menu-class (s/? string?)
              :mobile? (s/? boolean?)))

(defn tabbed-panel-menu [entries active-tab-id & [menu-class mobile?]]
  (let [menu-class (or menu-class "")
        render-entry
        (fn [{:keys [tab-id action content class disabled] :as entry}]
          (let [active? (= tab-id active-tab-id)]
            (when entry
              [:a {:key tab-id
                   :class (cond-> ""
                            active?  (str " active")
                            true     (str " item")
                            class    (str " " class)
                            disabled (str " disabled"))
                   :href (when (string? action) action)
                   :on-click
                   (util/wrap-user-event
                    (cond (and (seq? action)
                               (= (count action) 2))
                          #(dispatch [:navigate
                                      (first action) (second action)])

                          (vector? action)
                          #(dispatch [:navigate action])

                          (string? action) nil

                          :else action))}
               content])))]
    [:div.tabbed-panel
     [:div.ui
      {:class
       (str (sutil/num-to-english (count entries)) " item"
            " tabbed menu tabbed-panel " menu-class)}
      (doall
       (for [entry entries]
         (render-entry entry)))]]))
(s/fdef
 tabbed-panel-menu
 :args (s/cat :entries (s/coll-of ::menu-tab)
              :active-tab-id ::tab-id
              :menu-class (s/? string?)
              :mobile? (s/? boolean?)))

(defn out-link [url]
  [:div.item>a {:target "_blank" :href url}
   (util/url-domain url) nbsp [:i.external.icon]])

(defn document-link [url name]
  [:div.item>a.ui.large.label {:target "_blank" :href url}
   [:i.large.file.pdf.outline.icon] name])

(defn updated-time-label [dt & [shorten?]]
  (let [s (util/time-elapsed-string dt)
        label (if shorten?
                (->> (str/split s #" ")
                     butlast
                     (str/join " "))
                s)]
    [:div.ui.tiny.label label]))

(defn three-state-selection
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
            has-focus? #(-> (js/$ (str "#" (get-domid %)))
                            (.is ":focus"))
            set-focus #(js/setTimeout
                        (fn []
                          (-> (js/$ (str "#" (get-domid %)))
                              (.focus)))
                        25)
            set-value-focus #(do (set-answer! %) (set-focus %))
            render
            (fn [bvalue]
              (let [curval @value]
                [:div
                 (cond->
                     {:id (get-domid bvalue)
                      :class (bclass (nil? bvalue) (= curval bvalue))
                      :on-click (util/wrap-user-event
                                 #(set-value-focus bvalue))
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
                     (= curval bvalue)
                     (merge {:tabIndex "0"}))
                 (case bvalue false "No", nil "?", true "Yes")]))]
        [:div {:class class}
         [render false]
         [render nil]
         [render true]]))))

(defn three-state-selection-icons
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
               :on-click (util/wrap-user-event #(on-change false))}
      (get icons false)]
     [:div.ui {:class (bclass true (nil? curval))
               :on-click (util/wrap-user-event #(on-change nil))}
      (get icons nil)]
     [:div.ui {:class (bclass false (true? curval))
               :on-click (util/wrap-user-event #(on-change true))}
      (get icons true)]]))

(defn true-false-nil-tag
  "UI component for representing an optional boolean value.
  `value` is one of true, false, nil."
  [label value &
   {:keys [size style show-icon? value color?]
    :or {size "large", style {}, show-icon? true, color? true}}]
  (let [vclass (cond
                 (not color?) ""
                 (true? value) "green"
                 (false? value) "orange"
                 (string? value) value
                 :else "")
        iclass (case value
                 true "add circle icon"
                 false "minus circle icon"
                 "question circle icon")]
    [:div.ui.label
     {:class (str vclass " " size)
      :style style}
     (str label " ")
     (when (and iclass show-icon?)
       [:i {:class iclass
            :aria-hidden true
            :style {:margin-left "0.25em"
                    :margin-right "0"}}])]))

(defn ui-help-icon [& {:keys [size class style] :or {size ""}}]
  [:i.ui.grey.circle.question.mark.icon {:class (str size " " (or class ""))
                                         :style style}])

(defn with-ui-help-tooltip [element & {:keys [help-content help-element popup-options]}]
  (list
   ^{:key :tooltip-content}
   [with-tooltip
    element
    (merge {:delay {:show 300
                    :hide 0}
            :hoverable false}
           popup-options)]
   ^{:key :tooltip-help}
   [:div.ui.flowing.popup.transition.hidden.tooltip
    (cond help-content
          (doall (map-indexed #(if (string? %2)
                                 ^{:key %1}
                                 [:p %2]
                                 ^{:key %1}
                                 [:div %2])
                              help-content))
          help-element
          help-element)]))

(defn note-content-label [note-name content]
  (when (and (string? content)
             (not-empty (str/trim content)))
    [:div.ui.tiny.labeled.button.user-note
     [:div.ui.grey.button "Notes"]
     [:div.ui.basic.label content]]))

(defn ClipboardButton [target child]
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
              (let [clip (js/Clipboard. (r/dom-node el))]
                (.on clip "success" clip-success)
                clip))
            (reset-clip! [el] (reset! clip (get-clipboard el)))
            (component-did-mount [this] (reset-clip! this))
            (component-will-update [this _] (reset-clip! this))
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
        :component-will-update component-will-update
        :component-did-mount component-did-mount
        :component-will-unmount component-will-unmount
        :reagent-render render}))))

(defn CenteredColumn
  "Renders a grid column that will take up the full height of its row and
  vertically center its content within the column and row.

  This is done by wrapping the column content in a nested grid with CSS styles
  applied to several components."
  [content class]
  [:div {:class (str class " vertical-column")}
   [:div.ui.middle.aligned.grid>div.row>div.middle.aligned.column
    [:div.vertical-column-content content]]])

(defn TopAlignedColumn
  "Renders a grid column that will take up the full height of its row and
  vertically align its content to the top of the element.

  This is done by wrapping the column content in a nested grid with CSS styles
  applied to several components."
  [content class]
  [:div {:class (str class " vertical-column")}
   [:div.ui.top.aligned.grid>div.row>div.top.aligned.column
    [:div.vertical-column-content.top content]]])

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
            :class (cond-> ""
                     disabled (str " disabled"))}
     (not (nil? default-value)) (merge {:default-value default-value})
     (and (nil? default-value)
          (not (nil? value)))
     (merge {:value (if (in? [cljs.core/Atom
                              reagent.ratom/RAtom
                              reagent.ratom/RCursor
                              reagent.ratom/Reaction]
                             (type value))
                      @value
                      value)})
     (not (nil? placeholder)) (merge {:placeholder placeholder})
     (not (nil? on-mouse-up)) (merge {:on-mouse-up on-mouse-up})
     (not (nil? on-mouse-down)) (merge {:on-mouse-down on-mouse-down})
     autofocus (merge {:autoFocus true})
     read-only (merge {:readOnly true}))])

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
  }"
  [{:keys [error value on-change on-mouse-up on-mouse-down
           placeholder default-value label autofocus disabled read-only]}]
  [:div.field
   {:class (cond-> ""
             error (str " error"))}
   [:label label]
   [:input.ui.input
    (cond-> {:type "text"
             :on-change on-change
             :class (cond-> ""
                      disabled (str " disabled"))}
      (not (nil? default-value)) (merge {:default-value default-value})
      (and (nil? default-value)
           (not (nil? value)))
      (merge {:value (if (in? [cljs.core/Atom
                               reagent.ratom/RAtom
                               reagent.ratom/RCursor
                               reagent.ratom/Reaction]
                              (type value))
                       @value
                       value)})
      (not (nil? placeholder)) (merge {:placeholder placeholder})
      (not (nil? on-mouse-up)) (merge {:on-mouse-up on-mouse-up})
      (not (nil? on-mouse-down)) (merge {:on-mouse-down on-mouse-down})
      autofocus (merge {:autoFocus true})
      read-only (merge {:readOnly true}))]
   (when error
     [:div.ui.red.message error])])

(defn LabeledCheckbox
  "Props:
  {:checked?  <boolean>
   :on-change <fn>      ; a fn of event
   :label     <string>
  }"
  [{:keys [checked? on-change label]}]
  [:div.ui.checkbox
   {:style {:margin-right "0.5em"}}
   [:input {:type "checkbox"
            :on-change (util/wrap-user-event
                        on-change :timeout false)
            :checked checked?}]
   [:label label]])

(defn LabeledCheckboxField
  "Props:
  {:checked?  <boolean>
   :on-change <fn>      ; a fn of event
   :error     <string>  ; optional
   :label     <string>
  }"
  [{:keys [error on-change checked? label]}]
  [:div.field
   {:class (when error "error")}
   [:div.ui.checkbox
    [:input
     {:type "checkbox"
      :on-change (util/wrap-user-event
                  on-change :timeout false)
      :checked checked?}]
    [:label label]]
   (when error
     [:div.ui.red.message error])])

(defn SaveResetForm [& {:keys [can-save? can-reset? on-save on-reset saving?]}]
  [:div.ui.two.column.grid.save-reset-form
   [:div.column.save
    [:button.ui.fluid.right.labeled.positive.icon.button
     {:class (str (if can-save? "" "disabled")
                  " "
                  (if saving? "loading" ""))
      :on-click (util/wrap-user-event
                 #(when (and can-save? on-save (not saving?)) (on-save)))}
     "Save Changes"
     [:i.check.circle.outline.icon]]]
   [:div.column.reset
    [:button.ui.fluid.right.labeled.icon.button
     {:class (if can-reset? "" "disabled")
      :on-click (util/wrap-user-event
                 #(when (and can-reset? on-reset) (on-reset)))}
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
     (when message
       [:p {:style {:font-size "16px"
                    :font-weight "bold"}}
        message])]]
   [:div.ui.two.column.grid.confirm-cancel-form
    [:div.column
     [:button.ui.fluid.button
      {:on-click (util/wrap-user-event on-confirm) :class action-color}
      "Confirm"]]
    [:div.column
     [:button.ui.fluid.button
      {:on-click (util/wrap-user-event on-cancel)}
      "Cancel"]]]])

(defn UploadContainer
  "Create uploader form component."
  [childer upload-url on-success & args]
  (let [id (util/random-id)
        csrf-token (subscribe [:csrf-token])
        opts
        {:url upload-url
         :headers (when-let [token @csrf-token]
                    {"x-csrf-token" token})
         :maxFilesize (* 1000 10)
         :timeout (* 1000 60 60 4)}
        error-msg (r/atom nil)]
    (letfn [(init-dropzone [url]
              (->
               (js/Dropzone.
                (str "#" id)
                (clj->js
                 (->> {:previewTemplate
                       (-> js/document
                           (.querySelector (str "#" id "-template"))
                           .-innerHTML)
                       :previewsContainer (str "#" id "-preview")
                       :clickable (str "#" id "-button")}
                      (merge opts))))
               (.on "error"
                    (fn [file msg _]
                      (js/console.log (str "Upload error [" file "]: " msg))
                      (reset! error-msg msg)
                      true))
               (.on "success" on-success)))]
      (r/create-class
       {:reagent-render (fn [childer upload-url _ & args]
                          (apply childer id upload-url error-msg args))
        :component-did-mount #(init-dropzone upload-url)
        :display-name "upload-container"}))))

(defn- UploadButtonImpl [id & [upload-url error-msg text class]]
  [:div
   [:div.dropzone {:id id}
    [:button.ui.button
     {:id (str id "-button")
      ;; :type "submit"
      :style {:cursor "pointer"}
      :class (str (cond (util/full-size?) ""
                        (util/mobile?)    "tiny"
                        :else             "small")
                  " "
                  (if class class ""))}
     [:i.green.add.circle.icon]
     text]
    [:div.dropzone-previews {:id (str id "-preview")}]]
   [:div {:style {:display "none"}}
    [:div.dz-preview.dz-file-preview
     {:id (str id "-template")}
     [:div.ui.center.aligned.segment
      {:style {:margin-top "1em"}}
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
      [:div.dz-error-message
       [:span {:data-dz-errormessage ""}]]]]]])

(defn UploadButton [upload-url on-success text & [class]]
  [UploadContainer UploadButtonImpl upload-url on-success text class])

(defn WrapFixedVisibility [offset child]
  (let [on-update
        (fn [this]
          (let [el (r/dom-node this)]
            (-> (js/$ el)
                (.visibility
                 (clj->js {:type "fixed"
                           :offset (or offset 0)
                           :continuous true
                           :onUnfixed
                           #(-> (js/$ el) (.removeAttr "style"))
                           :onFixed
                           #(let [width (-> (js/$ el) (.parent) (.width))]
                              (-> (js/$ el) (.width width)))})))))]
    (r/create-class
     {:component-did-mount on-update
      :component-did-update on-update
      :reagent-render (fn [offset child] [:div.visibility-wrapper child])})))
