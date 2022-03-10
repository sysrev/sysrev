(ns sysrev.views.panels.project.define-labels
  (:require ["@insilica-org/material-table" :refer [default] :rename {default MaterialTable}]
            ["@material-ui/core" :as mui]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [re-frame.core :refer [dispatch dispatch-sync reg-event-db reg-sub
                                   subscribe trim-v]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.data.core :as data]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.util :as util :refer [css in? map-values parse-integer]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.panels.project.common :refer [ReadOnlyMessage]]
            [sysrev.views.semantic :as S :refer [Button Divider Form FormField Modal
                                                 ModalContent ModalDescription
                                                 ModalHeader Segment TextArea]]))

;; Convention -
;; A (new) label that exists in the client but not on the
;; server has a label-id of type string.
;; After label is saved to server the label-id type is uuid.

;; The jQuery plugin formBuilder is inspiration for the UI
;; repo: https://github.com/kevinchappell/formBuilder
;; demo: https://jsfiddle.net/kevinchappell/ajp60dzk/5/

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :labels :edit] :state state
                   :get [panel-get ::get]
                   :set [panel-set ::set])

(reg-sub      ::error-message #(panel-get % :error-message))
(reg-event-db ::error-message [trim-v]
              (fn [db [msg]] (panel-set db :error-message msg)))

(defn find-label [label-id labels]
  (some #(when (= (:label-id %) label-id) %) labels))

(reg-sub ::editing-label
         (fn []
           [(subscribe [::get :labels]) (subscribe [::get :editing-label-id]) (subscribe [::get :editing-root-label-id])])
         (fn [[labels-aux editing-label-id editing-root-label-id]]
           (let [labels (if editing-root-label-id
                          (-> (find-label editing-root-label-id (vals labels-aux)) :labels vals)
                          (vals labels-aux))]
             (find-label editing-label-id labels))))

(reg-sub ::is-editing-label?
         (fn []
           (subscribe [::get :editing-label-id]))
         some?)

(def initial-state {:read-only-message-closed? false})
(def new-label-id-prefix "new-label-")

(defn- saved-labels
  "Get the saved label values for the active project"
  []
  @(subscribe [:project/labels-raw]))

(defn ->local-labels
  [labels]
  (let [insert-answer #(assoc % :answer (case (:value-type %)
                                          "boolean" nil
                                          []))
        add-local-keys (fn [label]
                         (->> (boolean
                               (if (= (:value-type label) "group")
                                 (or (seq (:errors label))
                                     (seq (->> label :labels vals (map :errors) (remove nil?))))
                                 (seq (:errors label))))
                              (assoc label :editing?)))
        set-inclusion #(cond-> %
                         (not (contains? % :inclusion))
                         (assoc :inclusion (pos? (-> % :definition :inclusion-values count))))]
    (map-values (comp insert-answer add-local-keys set-inclusion) labels)))

(defn to-local-labels
  "Convert labels map to format used by local namespace state."
  [labels]
  (let [converted-labels (->local-labels labels)
        f (fn [[k v]] (if (= :labels k)
                        [:labels (->local-labels v)]
                        [k v]))]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) converted-labels)))

(defn set-local-labels!
  "Sets value of local labels state from labels map, applying some
  conversions to format correctly for local state."
  [labels]
  (swap! state assoc-in [:labels] (to-local-labels labels)))

(defn ensure-state []
  (when (nil? @state)              (reset! state initial-state))
  (when (empty? (:labels @state))  (set-local-labels! (saved-labels))))

(defn- get-local-labels []
  (get-in @state [:labels]))

(defn max-project-ordering
  "Obtain the max-project-ordering from the local state of labels"
  []
  (apply max (map :project-ordering (vals (get-local-labels)))))

(defn max-group-label-ordering
  "Obtain the max-project-ordering for a group label"
  [group-label-id]
  (or (->> (-> (get-local-labels) (get group-label-id) (get :labels))
           vals
           (map :project-ordering)
           (apply max))
      0))

(defn default-answer [value-type]
  (case value-type
    "boolean"      nil
    "string"       []
    "categorical"  []
    nil))

(defn create-blank-group-label [value-type label-id]
  {:definition {:multi? true}
   :inclusion false
   :category "extra"
   :name (str value-type (util/random-id))
   :project-ordering (inc (max-project-ordering))
   :label-id label-id ;; this is a string, to distinguish unsaved labels
   :project-id (active-project-id @app-db)
   :enabled true
   :value-type value-type
   :required false
     ;;; these last fields are used only internally by this namespace;
     ;;; filtered before exporting elsewhere
   :answer (default-answer value-type)
   :editing? true
   :errors (list)
   :labels {}})

(defn create-blank-label [value-type project-ordering]
  (let [label-id (str new-label-id-prefix (util/random-id))]
    (if (= value-type "group")
      (create-blank-group-label value-type label-id)
      {:definition (case value-type
                     "boolean"      {:inclusion-values []}
                     "string"       {:multi? false :max-length 100}
                     "categorical"  {:inclusion-values [] :multi? true}
                     "annotation"  {}
                     {})
       :inclusion false
       :category "extra"
       :name (str value-type (util/random-id))
       :project-ordering project-ordering
       :label-id label-id ;; this is a string, to distinguish unsaved labels
       :project-id (active-project-id @app-db)
       :enabled true
       :value-type value-type
       :required false
     ;;; these last fields are used only internally by this namespace;
     ;;; filtered before exporting elsewhere
       :answer (default-answer value-type)
       :editing? true
       :errors (list)})))

(defn add-new-label!
  "Add a new label in local namespace state."
  [label]
  (swap! state assoc-in [:labels (:label-id label)] label)
  (dispatch [::set :editing-label-id (:label-id label)]))

(defn add-new-group-label!
  [labels-atom label]
  (swap! labels-atom assoc (:label-id label) label))

(defn local-label->server-label
  "Convert labels map to format used by global client state and server."
  [labels]
  (->> (vals labels)
       ;; FIX: run db migration to fix label `category` values;
       ;; may affect label tooltips and sorting
       (map (fn [{:keys [definition] :as label}]
              (let [{:keys [inclusion-values]} definition]
                ;; set correct `category` value based on `inclusion-values`
                (assoc label :category
                       (if (not-empty inclusion-values)
                         "inclusion criteria" "extra")))))
       (map #(dissoc % :editing? :answer :errors :inclusion))
       (map #(hash-map (:label-id %) %))
       (apply merge)))

(defn to-global-labels
  [labels]
  (let [converted-labels (local-label->server-label labels)
        f (fn [[k v]] (if (= :labels k)
                        [:labels (local-label->server-label v)]
                        [k v]))]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) converted-labels)))

(defn labels-synced?
  "Are the labels synced with the global app-db?"
  []
  (= (saved-labels) (to-global-labels (get-local-labels))))

(defn sync-to-server
  "Send local labels to server to update DB."
  []
  (when (not (labels-synced?))
    (dispatch [:action [:labels/sync-project-labels
                        (active-project-id @app-db)
                        (to-global-labels (get-local-labels))]])))

(defn set-app-db-labels!
  "Overwrite the value of global project labels map."
  [labels]
  (let [project-id (active-project-id @app-db)
        app-labels (r/cursor app-db [:data :project project-id :labels])]
    (reset! app-labels labels)))

(defn reset-local-label!
  "Resets local state of label to saved state, or discards unsaved."
  [labels-atom root-label-id label-id]
  (if (string? label-id)
    (swap! labels-atom dissoc label-id)
    ;; this needs to be refactored to consider the root label
    (if (= root-label-id "na")
      (reset! (r/cursor labels-atom [label-id])
              (-> (saved-labels) to-local-labels (get label-id)))
      (reset! (r/cursor labels-atom [label-id])
              (-> (saved-labels) to-local-labels (get-in [root-label-id :labels label-id]))))))

(defn DisableEnableLabelButton [labels-atom root-label-id label]
  (let [{:keys [label-id enabled]} @label]
    (when-not (or (= (:name @label) "overall include")
                  (string? label-id))  ;; don't show this for unsaved labels
      [:button.ui.small.fluid.labeled.icon.button
       {:class (css [(not enabled) "primary"])
        :type "button"
        :on-click (util/wrap-user-event
                   #(do (reset-local-label! labels-atom root-label-id label-id)
                        (swap! (r/cursor label [:enabled]) not)
                        (sync-to-server))
                   :prevent-default true)}
       [:i {:class (css "circle" [enabled "minus" :else "plus"] "icon")}]
       (if enabled "Disable" "Enable") " Label"])))

(defn DetachLabelButton [label]
  (let [{:keys [label-id]} @label
        project-id @(subscribe [:active-project-id])]
    (when-not (string? label-id) ;; don't show this for unsaved labels
      [:button.ui.small.fluid.labeled.icon.button.secondary
       {:type "button"
        :on-click (util/wrap-user-event
                   #(dispatch [:action [:labels/detach project-id
                                        {:label-id label-id}]])
                   :prevent-default true)}
       [:i {:class (css "unlink" "icon")}]
       "Detach Label"])))

(defn CancelDiscardButton [labels-atom root-label-id label]
  (let [{:keys [label-id]} @label
        text (if (string? label-id) "Discard" "Cancel")]
    [:button.ui.small.fluid.labeled.icon.button
     {:on-click (fn [e]
                  (.preventDefault e)
                  (.stopPropagation e)
                  (reset-local-label! labels-atom root-label-id label-id)
                  (dispatch-sync [::set :editing-root-label-id nil])
                  (dispatch-sync [::set :editing-label-id nil]))}
     [:i.circle.times.icon] text]))

(defn save-request-active? []
  (action/running? :labels/sync-project-labels))

(defn SaveLabelButton [_label & {:keys [on-click]}]
  [:button.ui.small.fluid.positive.labeled.icon.button
   {:type "submit"
    :class (css [(save-request-active?) "loading"]
                [(labels-synced?) "disabled"])
    :on-click (fn [ev]
                (dispatch [:alert {:content "Saving..." :opts {:success true}}])
                (on-click ev))}
   [:i.check.circle.outline.icon] "Save"])

(def-action :labels/get-share-code
  :uri (fn [] "/api/get-label-share-code")
  :content (fn [project-id label-id]
             {:project-id project-id :label-id label-id})
  :process (fn [_ [_] {:keys [success share-code]}]
             (when success
               {:dispatch-n [[::set [:share-label-modal :open] true]
                             [::set [:share-label-modal :share-code] share-code]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:alert {:opts {:error true}
                                  :content (str "There was an error generating the share code")}]}))

(defn ShareLabelButton []
  (let [modal-state-path [:share-label-modal]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        share-code (r/cursor state (concat modal-state-path [:share-code]))]
    (fn []
      [Modal {:class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Share Label"]
       [ModalContent
        (if @share-code
          [:div
           [:p "Copy this code to share your label:"]
           [:div.ui.card.fluid {:style {:margin-bottom "16px"}}
            [:div.content
             [:span.share-code.ui.text.black @share-code]]]]
          [:div.ui.segment {:style {:height "100px"}}
           [:div.ui.active.dimmer
            [:div.ui.loader]]])
        [ModalDescription
         (when @share-code
           [Button {:id "share-label-button-copy"
                    :on-click (util/wrap-prevent-default
                               (fn []
                                 (-> (aget js/navigator "clipboard") (.writeText @share-code))
                                 (dispatch [:alert {:content "Answers copied to clipboard" :opts {:success true}}])
                                 (reset! modal-open false)))
                    :primary true}
            "Copy"])]]])))

(defn- AddLabelButton [value-type add-label-fn & [max-ordering]]
  [:button.ui.fluid.large.labeled.icon.button
   {:on-click  (fn [e]
                 (.preventDefault e)
                 (.stopPropagation e)
                 (add-label-fn (create-blank-label value-type (if max-ordering
                                                                (inc max-ordering)
                                                                (inc (max-project-ordering))))))}
   [:i.plus.circle.icon]
   (str "Add " (str/capitalize value-type) " Label")])

(def-action :labels/import
  :uri (fn [] "/api/import-label")
  :content (fn [project-id {:keys [share-code]}] {:project-id project-id
                                                  :share-code share-code})
  :process (fn [_ [_] {:keys [success labels message]}]
             (if success
               (do
                 (set-app-db-labels! labels)
                 (set-local-labels! labels)
                 {:dispatch-n [[:alert {:content "Label imported successfully!"
                                        :opts {:success true}}]
                               [::set [:import-label-modal :open] false]]})
               {:dispatch-n [[::set [:import-label-modal :open] false]
                             [:alert {:content message
                                      :opts {:error true}}]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch-n [[::set [:import-label-modal :open] false]
                            [:alert {:content (str "There was an error importing this label, "
                                                   "please check the share code and try again.")
                                     :opts {:error true}}]]}))

(def-action :labels/detach
  :uri (fn [] "/api/detach-label")
  :content (fn [project-id {:keys [label-id]}] {:project-id project-id
                                                :label-id label-id})
  :process (fn [_ [_] {:keys [success labels message]}]
             (if success
               (do
                 (set-app-db-labels! labels)
                 (set-local-labels! labels)
                 {:dispatch-n [[:alert {:content "Label detached successfully!"
                                        :opts {:success true}}]]})
               {:dispatch-n [[:alert {:content message
                                      :opts {:error true}}]]}))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch-n [[:alert {:content (str "There was an error detaching this label, "
                                                   "please try again later.")
                                     :opts {:error true}}]]}))

(defn- ImportLabelButton []
  (let [modal-state-path [:import-label-modal]
        modal-open (r/cursor state (concat modal-state-path [:open]))
        project-id @(subscribe [:active-project-id])
        share-code (r/atom "")]
    (fn []
      [Modal {:trigger (r/as-element
                        [:button.ui.fluid.large.labeled.icon.button
                         {:on-click  (fn [e]
                                       (when (.-preventDefault e)
                                         (.preventDefault e))
                                       (when (.-stopPropagation e)
                                         (.stopPropagation e)))}
                         [:i.file.import.icon]
                         "Import Label"])
              :class "tiny"
              :open @modal-open
              :on-open #(reset! modal-open true)
              :on-close #(reset! modal-open false)}
       [ModalHeader "Import Label"]
       [ModalContent
        [ModalDescription
         [Form {:on-submit (util/wrap-prevent-default
                            #(dispatch [:action [:labels/import project-id
                                                 {:share-code @share-code}]]))}
          [FormField
           [:label "Please input your share code:"]
           [TextArea {:label "Share code"
                      :id "share-code-input"
                      :rows 5
                      :on-change (util/on-event-value #(reset! share-code %))}]]
          [Button {:primary true
                   :id "import-label-btn"}
           "Import"]]]]])))

(def-action :labels/sync-project-labels
  :uri (fn [] "/api/sync-project-labels")
  :content (fn [project-id labels] {:project-id project-id :labels labels})
  :process (fn [_ _ {:keys [valid? labels]}]
             (if valid? ;; update successful?
               ;; update (1) app-wide project data and (2) local namespace state
               (do (set-app-db-labels! labels)
                   (set-local-labels! labels)
                   (dispatch [::set :editing-root-label-id nil])
                   (dispatch [::set :editing-label-id nil]))
               ;; update local state (includes error messages)
               (set-local-labels! labels))
             {})
  :on-error (fn [{:keys [error]} _ _]
              {:dispatch-n [[::set [:error-message] (:message error)]]}))

(defn FormLabelWithTooltip [text tooltip-content]
  [ui/UiHelpTooltip [:label text [ui/UiHelpIcon]]
   :help-content tooltip-content
   :options {:mouse-enter-delay 500}])

(def label-settings-config
  {:short-label  {:display "Name"}
   :question     {:display "Question"
                  :tooltip ["Describe the meaning of this label for reviewers."
                            "Displayed as tooltip in review interface."]}
   :required     {:display "Require answer"
                  :tooltip ["Require users to provide an answer for this label before saving article."]}
   :consensus    {:display "Require user consensus"
                  :tooltip ["Check answers for consensus among users."
                            "Articles will be marked as conflicted if user answers are not identical."]}
   :max-length   {:path [:definition :max-length]
                  :display "Max length"}
   :regex        {:path [:definition :regex]
                  :display "Pattern (regex)"
                  :tooltip ["Require match against regular expression."]
                  :optional true}
   :examples     {:path [:definition :examples]
                  :display "Examples (comma-separated)"
                  :tooltip ["Examples of possible label values for reviewers."
                            "Displayed as tooltip in review interface."]
                  :optional true}
   :default-value {:path [:definition :default-value]
                   :display "Default label value"
                   :tooltip ["Default label value"]
                   :optional true}
   :all-values   {:path [:definition :all-values]
                  :display "Categories (comma-separated options)"
                  :tooltip ["List of values allowed for label."
                            "Reviewers may select multiple values in their answers."]
                  :placeholder "one,two,three"}
   :multi?       {:path [:definition :multi?]
                  :display "Allow multiple values"
                  :tooltip ["Allow answers to contain multiple string values."]}
   :inclusion    {:display "Inclusion criteria"
                  :tooltip ["Define a relationship between this label and article inclusion."
                            "Users will be warned if their answers contradict the value selected for article inclusion."]}})

(defn- label-setting-field-args
  "Creates map of standard arguments to field component function for a
  label setting (i.e. entry in label-settings-config)."
  [setting & [errors extra]]
  (when-let [{:keys [path display tooltip placeholder optional]}
             (get label-settings-config setting)]
    (cond-> {:field-class (-> (str "field-" (name setting))
                              (str/split #"\?") ;; remove ? from css class
                              (str/join))}
      errors       (assoc :error (get-in errors (or path [setting])))
      display      (assoc :label display)
      tooltip      (assoc :tooltip tooltip)
      placeholder  (assoc :placeholder placeholder)
      optional     (assoc :optional optional)
      extra        (merge extra))))

(defn make-args [setting extra errors]
  (label-setting-field-args setting @errors extra))

(defn LabelEditForm [labels-atom root-label-id label]
  (let [show-error-msg #(some->> (or % @(subscribe [::error-message]))
                                 (vector :div.ui.red.message))
        value-type (r/cursor label [:value-type])
        ;;; all types
        ;; required, string
        short-label (r/cursor label [:short-label])
        ;; boolean (default false)
        required (r/cursor label [:required])
        ;; boolean (default false, available if :required is true)
        consensus (r/cursor label [:consensus])
        ;; required, string
        question (r/cursor label [:question])
        ;;
        definition (r/cursor label [:definition])
        ;;; type=(boolean or categorical)
        ;; boolean, activates interface for defining inclusion-values
        inclusion (r/cursor label [:inclusion])
        ;;; type=(boolean or categorical)
        ;; optional, vector of (boolean or string)
        ;; for categorical, the values here must also be in `:all-values`
        inclusion-values (r/cursor definition [:inclusion-values])
        ;;; type=(categorical or string)
        ;; required, boolean
        multi? (r/cursor definition [:multi?])
        ;;; type=categorical or annotation
        ;; required, vector of strings
        all-values (r/cursor definition [:all-values])
        ;;; type=string
        ;; optional, vector of strings
        regex (r/cursor definition [:regex])
        ;; optional, vector of strings
        examples (r/cursor definition [:examples])
        ;; required, integer
        max-length (r/cursor definition [:max-length])
        ;; 
        validatable-label? (r/cursor definition [:validatable-label?])
        ;; 
        hidden-label? (r/cursor definition [:hidden-label?])
        ;; 
        default-value (r/cursor definition [:default-value])
        ;;;
        errors (r/cursor label [:errors])
        is-new? (and (string? (:label-id @label)) (str/starts-with? (:label-id @label) new-label-id-prefix))
        is-owned? (or is-new? (= (:owner-project-id @label) (:project-id @label)))]
    [:form.ui.form.define-label {:on-submit (util/wrap-user-event
                                             (fn [_]
                                               (if (and is-owned? (not (labels-synced?)))
                                                  ;; save on server
                                                 (sync-to-server)
                                                  ;; just reset editing
                                                 (reset! (r/cursor label [:editing?]) false)))
                                             :prevent-default true)}
     (when (string? @value-type)
       [:h5.ui.dividing.header.value-type
        (str (str/capitalize @value-type) " Label")])

     (when-not is-owned?
       [:p "This is a shared label. In order to make edits you can detach it from its parent project, but you won't leverage from data in other projects anymore."])

     ;; short-label
     [ui/TextInputField
      (make-args :short-label
                 {:value short-label
                  :disabled (not is-owned?)
                  :on-change #(reset! short-label (-> % .-target .-value))}
                 errors)]
     ;; question
     [ui/TextInputField
      (make-args :question
                 {:value question
                  :disabled (not is-owned?)
                  :on-change #(reset! question (-> % .-target .-value))}
                 errors)]
     ;; max-length on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        (make-args :max-length
                   {:value max-length
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (reset! max-length (or (parse-integer value) value)))}
                   errors)])
     ;; regex on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        (make-args :regex
                   {:value (or (not-empty (first @regex)) "")
                    :disabled (not is-owned?)
                    :on-change (util/on-event-value
                                #(reset! regex (some-> % str/trim not-empty vector)))}
                   errors)])
     ;; examples on a string label
     (when (= @value-type "string")
       [ui/TextInputField
        (make-args :examples
                   {:value (str/join "," @examples)
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! examples nil)
                                    (reset! examples (str/split value #"," -1))))}
                   errors)])
     (when (= @value-type "categorical")
       ;; FIX: whitespace not trimmed from input strings;
       ;; need to run db migration to fix all existing values
       [ui/TextInputField
        (make-args :all-values
                   {:value (str/join "," @all-values)
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! all-values nil)
                                    (reset! all-values (str/split value #"," -1))))}
                   errors)])
     (when (= @value-type "annotation")
       ;; FIX: whitespace not trimmed from input strings;
       ;; need to run db migration to fix all existing values
       [ui/TextInputField
        (make-args :all-values
                   {:value (str/join "," @all-values)
                    :label "Entities (comma-separated options)"
                    :tooltip ["Entities to annotate."]
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! all-values nil)
                                    (reset! all-values (str/split value #"," -1))))}
                   errors)])




     ;; required
     [ui/LabeledCheckboxField
      (make-args :required
                 {:checked? @required
                  :disabled (not is-owned?)
                  :on-change #(let [value (-> % .-target .-checked boolean)]
                                (reset! required value)
                                (when (false? value)
                                  (reset! consensus false)))}
                 errors)]
     ;; consensus
     [ui/LabeledCheckboxField
      (make-args :consensus
                 {:checked? @consensus
                  :disabled (not is-owned?)
                  :on-change #(reset! consensus (-> % .-target .-checked boolean))}
                 errors)]
     ;; multi?
     (when (= @value-type "string")
       [ui/LabeledCheckboxField
        (make-args :multi?
                   {:checked? @multi?
                    :disabled (not is-owned?)
                    :on-change #(reset! multi? (-> % .-target .-checked boolean))}
                   errors)])
     ;; inclusion checkbox
     (when (in? ["boolean" "categorical"] @value-type)
       [ui/LabeledCheckboxField
        (make-args :inclusion
                   {:checked? @inclusion
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-checked boolean)]
                                  (reset! inclusion value)
                                  (when (false? value)
                                    (reset! inclusion-values [])))}
                   errors)])
     ;; inclusion-values for categorical label
     (when (and (= @value-type "categorical")
                (not (false? @inclusion))
                (seq @all-values))
       (let [error (get-in @errors [:definition :inclusion-values])]
         [:div.field.inclusion-values {:class (when error "error")
                                       :style {:width "100%"}}
          [FormLabelWithTooltip
           "Inclusion values"
           ["Answers containing any of these values will indicate article inclusion."
            "Non-empty answers otherwise will indicate exclusion."]]
          (doall (for [option-value @all-values]
                   ^{:key (gensym option-value)}
                   [ui/LabeledCheckbox
                    {:checked? (contains? (set @inclusion-values) option-value)
                     :disabled (not is-owned?)
                     :on-change
                     #(reset! inclusion-values
                              (if (-> % .-target .-checked)
                                (into [] (conj @inclusion-values option-value))
                                (into [] (remove (partial = option-value) @inclusion-values))))
                     :label option-value}]))
          [show-error-msg error]]))

     ;; inclusion-values for boolean label
     (when (and (= @value-type "boolean")
                (not (false? @inclusion)))
       (let [error (get-in @errors [:definition :inclusion-values])]
         [:div.field.inclusion-values {:class (when error "error")
                                       :style {:width "100%"}}
          [FormLabelWithTooltip
           "Inclusion value"
           ["Select which value should indicate article inclusion."]]
          [ui/LabeledCheckbox
           {:checked? (contains? (set @inclusion-values) false)
            :disabled (not is-owned?)
            :on-change #(let [checked? (-> % .-target .-checked)]
                          (reset! inclusion-values (if checked? [false] [])))
            :label "No"}]
          [ui/LabeledCheckbox
           {:checked? (contains? (set @inclusion-values) true)
            :disabled (not is-owned?)
            :on-change #(let [checked? (-> % .-target .-checked)]
                          (reset! inclusion-values (if checked? [true] [])))
            :label "Yes"}]
          [show-error-msg error]]))

     (when (= @value-type "string")
       (let [error (get-in @errors [:definition :validatable-label?])]
         [:div.field.validatable-label {:class (when error "error")
                                        :style {:width "100%"}}
          [FormLabelWithTooltip
           "Use identifiers.org resolver?"
           ["Resolve this label values against identifiers.org"]]
          [ui/LabeledCheckbox
           {:checked? (not @validatable-label?)
            :disabled (not is-owned?)
            :on-change #(let [checked? (-> % .-target .-checked)]
                          (reset! validatable-label? (not checked?)))
            :label "No"}]
          [ui/LabeledCheckbox
           {:checked? @validatable-label?
            :disabled (not is-owned?)
            :on-change #(let [checked? (-> % .-target .-checked)]
                          (reset! validatable-label? checked?))
            :label "Yes"}]
          [show-error-msg error]]))


     (case @value-type
       "boolean"
       [:div.field.inclusion-values {:style {:width "100%"}}
        [FormLabelWithTooltip
         "Default Value"
         ["Please select a default value for this label"]]
        [ui/LabeledCheckbox
         {:checked? (true? @default-value)
          :disabled (not is-owned?)
          :on-change #(let [checked? (-> % .-target .-checked)]
                        (when checked?
                          (reset! default-value true)))
          :label "True"}]
        [ui/LabeledCheckbox
         {:checked? (false? @default-value)
          :disabled (not is-owned?)
          :on-change #(let [checked? (-> % .-target .-checked)]
                        (when checked?
                          (reset! default-value false)))
          :label "False"}]]

       "categorical"
       [ui/TextInputField
        (make-args :default-value
                   {:value (str/join "," @default-value)
                    :display "Default label value (comma-separated)"
                    :prompt "Comma separated default values"
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! default-value nil)
                                    (reset! default-value (str/split value #"," -1))))}
                   errors)]

       "string"
       [ui/TextInputField
        (make-args :default-value
                   {:label "Default value"
                    :value (str/join "," @default-value)
                    :tooltip ["Default value"]
                    :disabled (not is-owned?)
                    :on-change #(let [value (-> % .-target .-value)]
                                  (if (empty? value)
                                    (reset! default-value nil)
                                    (reset! default-value (str/split value #"," -1))))}
                   errors)]

       [:span])
     (let [error (get-in @errors [:definition :hidden-label?])]
       [:div.field.validatable-label {:class (when error "error")
                                      :style {:width "100%"}}
        [FormLabelWithTooltip
         "Hide label?"
         ["Hide label from the reviewer"]]

        [ui/LabeledCheckbox
         {:checked? (not @hidden-label?)
          :disabled (not is-owned?)
          :on-change #(let [checked? (-> % .-target .-checked)]
                        (reset! hidden-label? (not checked?)))
          :label "No"}]
        [ui/LabeledCheckbox
         {:checked? @hidden-label?
          :disabled (not is-owned?)
          :on-change #(let [checked? (-> % .-target .-checked)]
                        (reset! hidden-label? checked?))
          :label "Yes"}]
        [show-error-msg error]])
     (when is-owned?
       [:div.field {:style {:margin-bottom "0.75em"}}
        [:div.ui.two.column.grid {:style {:margin "-0.5em"}}
         [:div.column {:style {:padding "0.5em"}} [SaveLabelButton label]]
         [:div.column {:style {:padding "0.5em"}} [CancelDiscardButton labels-atom root-label-id label]]]])
     (if is-owned?
       [:div.field [DisableEnableLabelButton labels-atom root-label-id label]]
       [:div.field {:style {:margin-bottom "0.75em"}}
        [:div.ui.two.column.grid {:style {:margin "-0.5em"}}
         [:div.column {:style {:padding "0.5em"}} [DisableEnableLabelButton labels-atom root-label-id label]]
         [:div.column {:style {:padding "0.5em"}} [DetachLabelButton label]]]])]))

(defn GroupLabelEditForm [labels-atom label]
  (let [short-label (r/cursor label [:short-label])
        root-label-id (r/cursor label [:label-id])
        definition (r/cursor label [:definition])
        errors (r/cursor label [:errors])
        multi? (r/cursor definition [:multi?])
        labels (r/cursor label [:labels])
        is-new? (and (string? (:label-id @label)) (str/starts-with? (:label-id @label) new-label-id-prefix))
        is-owned? (or is-new? (= (:owner-project-id @label) (:project-id @label)))]
    [:div.ui.form.define-group-label {:id (str "group-label-id-" @root-label-id)}
     ;; short-label
     [ui/TextInputField
      (make-args :short-label
                 {:disabled (not is-owned?)
                  :value short-label
                  :on-change #(reset! short-label (-> % .-target .-value))}
                 errors)]
     ;; multi?
     [ui/LabeledCheckboxField
      (make-args :multi?
                 {:checked? @multi?
                  :disabled (not is-owned?)
                  :on-change #(reset! multi? (-> % .-target .-checked boolean))}
                 errors)]
     [:div {:class (css "sub-labels-edit-form"
                        [(:labels-error @errors) "error"])}
      (when (:labels-error @errors)
        [:div.ui.red.message (:labels-error @errors)])
      ;; enabled labels
      (doall (for [label (->> (vals @labels)
                              (sort-by :project-ordering <)
                              (filter :enabled))]
               ^{:key (:label-id label)}
               [:div [LabelEditForm labels @root-label-id
                      (r/cursor labels [(:label-id label)])]]))
      (let [disabled-labels (->> (vals @labels)
                                 (sort-by :project-ordering <)
                                 (remove :enabled))]
        (when (seq disabled-labels)
          ;; disabled labels
          [Divider]
          [:div "Disabled Labels"
           (doall (for [label disabled-labels] ^{:key (:label-id label)}
                       [:div [LabelEditForm labels @root-label-id
                              (r/cursor labels [(:label-id label)])]]))]))
      [Divider]
      [:div.ui.one.column.stackable.grid
       [:div.column.group [AddLabelButton "boolean" (partial add-new-group-label! labels)
                           (max-group-label-ordering @root-label-id)]]
       [:div.column.group [AddLabelButton "categorical" (partial add-new-group-label! labels)
                           (max-group-label-ordering @root-label-id)]]
       [:div.column.group [AddLabelButton "string" (partial add-new-group-label! labels)
                           (max-group-label-ordering @root-label-id)]]]]
     [:div.field {:style {:margin-bottom "0.75em"}}
      (when is-owned?
        [:div.ui.two.column.grid {:style {:margin "-0.5em"}}
         [:div.column {:id "group-id-submit" :style {:padding "0.5em"}}
          [SaveLabelButton label :on-click (util/wrap-user-event
                                            (fn [_]
                                              (if (not (labels-synced?))
                                                 ;; save on server
                                                (sync-to-server)
                                                 ;; just reset editing
                                                (reset! (r/cursor label [:editing?]) false)))
                                            :prevent-default true)]]
         [:div.column {:style {:padding "0.5em"}}
          [CancelDiscardButton labels-atom "na" label]]])]]))

(defn- UpgradeMessage []
  [Segment {:style {:text-align "center"}
            :id "group-label-paywall"}
   [:div
    [:h2 "Group Labels are available for Pro Accounts" [:br]
     "Sign up at " [:a {:href "/pricing"} "sysrev.com/pricing"]]
    [:span {:font-size "0.5em !important"}
     "Read a " [:a {:href "https://blog.sysrev.com/group-labels/"
                    :target "_blank"} "blog post"]
     ", see a " [:a {:href "/o/2/p/31871"
                     :target "_blank"} "sample project"]
     " or view a " [:a {:href "https://youtu.be/aKhg-hHea88"
                        :target "_blank"} "demo video"]
     " to learn more about this feature"]
    [:br]]])


(defn SideEditableView [labels-atom]
  (let [editing-label (subscribe [::editing-label])
        editing-root-label-id (subscribe [::get :editing-root-label-id])]
    (when @editing-label
      [:div.column
       [:div.ui.segment
        (if (= (:value-type @editing-label) "group")
          [GroupLabelEditForm labels-atom (r/cursor state [:labels (:label-id @editing-label)])]
          (if @editing-root-label-id
            [LabelEditForm labels-atom @editing-root-label-id
             (r/cursor state [:labels @editing-root-label-id :labels (:label-id @editing-label)])]
            [LabelEditForm labels-atom "na" (r/cursor state [:labels (:label-id @editing-label)])]))]])))

(defn re-order [labels-atom root-label-id label-id diff]
  (let [indexed-labels (->> (if (= root-label-id "na")
                              (vals @labels-atom)
                              (vals (get-in @labels-atom [root-label-id :labels])))
                            (sort-by (juxt #(not (:enabled %)) :project-ordering))
                            (map-indexed vector))
        label-idx (some #(when (= (-> % second :label-id) label-id)
                           (-> % first))
                        indexed-labels)
        new-label-idx (-> (+ label-idx diff)
                          (min (dec (count indexed-labels)))
                          (max 0))
        new-labels (mapv (fn [[idx label]]
                           (cond
                             (= idx new-label-idx)
                             (assoc label :project-ordering label-idx)

                             (= idx label-idx)
                             (assoc label :project-ordering new-label-idx)

                             :else
                             (assoc label :project-ordering idx)))
                         indexed-labels)]
    (if (= root-label-id "na")
      (doall
       (for [label new-labels]
         (reset! (r/cursor labels-atom [(:label-id label) :project-ordering]) (:project-ordering label))))
      (reset! (r/cursor labels-atom [root-label-id :labels])
              (->> new-labels
                   (map #(vector (:label-id %) %))
                   (into {}))))
    (sync-to-server)))

(defn LabelsTable [labels-atom]
  (let [project-id @(subscribe [:active-project-id])
        is-editing-label? @(subscribe [::is-editing-label?])
        label-filters @(subscribe [::get :label-filters])
        self-email @(subscribe [:self/email])
        status-filter-fn (fn [label]
                           (case (:status label-filters)
                             "enabled" (:enabled label)
                             "disabled" (not (:enabled label))
                             true))
        filter-labels (fn [labels]
                        (filter (fn [label]
                                  (let [labels (->> label :labels vals)]
                                    (if (empty? labels)
                                      (status-filter-fn label)
                                      (some status-filter-fn labels))))
                                labels))
        cols (concat
              (when self-email
                [{:field "ordering-display-1" :title "#"  :defaultSort "asc" :type "numeric"
                  :render (fn [rowData]
                            (let [v (aget rowData "ordering-display-1")]
                              (when-not (str/blank? v)
                                (r/as-element
                                 [:div {:style {:text-align "center"
                                                :min-width "110px"}}
                                  [:> mui/IconButton
                                   {:on-click (fn [ev]
                                                (.stopPropagation ev)
                                                (re-order labels-atom (or (.-parentId ^js rowData) "na") (.-id ^js rowData) -1))}
                                   [:> mui/Icon "keyboard_arrow_up"]]
                                  " " v " "
                                  [:> mui/IconButton
                                   {:on-click (fn [ev]
                                                (.stopPropagation ev)
                                                (re-order labels-atom (or (.-parentId ^js rowData) "na") (.-id ^js rowData) 1))}
                                   [:> mui/Icon "keyboard_arrow_down"]]]))))
                  :headerStyle {:width "10px"}}
                 {:field "ordering-display-2" :title "#" :width "10px"
                  :defaultSort "asc" :type "numeric"
                  :render (fn [rowData]
                            (let [v (aget rowData "ordering-display-2")]
                              (when-not (str/blank? v)
                                (r/as-element
                                 [:div {:style {:text-align "center"
                                                :min-width "110px"}}
                                  [:> mui/IconButton
                                   {:on-click (fn [ev]
                                                (.stopPropagation ev)
                                                (re-order labels-atom (or (.-parentId ^js rowData) "na") (.-id ^js rowData) -1))}
                                   [:> mui/Icon "keyboard_arrow_up"]]
                                  " " v " "
                                  [:> mui/IconButton
                                   {:on-click (fn [ev]
                                                (.stopPropagation ev)
                                                (re-order labels-atom (or (.-parentId ^js rowData) "na") (.-id ^js rowData) 1))}
                                   [:> mui/Icon "keyboard_arrow_down"]]]))))}])
              [{:field "short-label" :title "Name"}
               {:field "value-type" :title "Type" :hidden is-editing-label?}
               {:field "consensus" :title "Consensus" :hidden is-editing-label?}
               {:field "inclusion" :title "Inclusion" :hidden is-editing-label?}
               {:field "required" :title "Required" :hidden is-editing-label?}])
        rows (->> @labels-atom vals
                  filter-labels
                  (mapcat
                   (fn [label]
                     (let [is-new? (and (string? (:label-id label)) (str/starts-with? (:label-id label) new-label-id-prefix))
                           is-owned? (or is-new? (= (:owner-project-id label) (:project-id label)))]
                       (concat
                        [(-> label
                             (assoc :id (:label-id label))
                             (assoc :isNew is-new?)
                             (assoc :isOwned is-owned?)
                             (assoc :valueType (:value-type label))
                             (assoc :name (:name label))
                             (assoc :ordering-display-1 (inc (:project-ordering label))))]
                        (->> label :labels vals filter-labels
                             (mapv #(assoc %
                                           :id (:label-id %)
                                           :short-label-2 (str " — " (:short-label label))
                                           :ordering-display-2 (inc (:project-ordering %))
                                           :isNew is-new?
                                           :isOwned is-owned?
                                           :parentId (:label-id label)))))))))]
    [:div.ui.equal.width.aligned.grid
     [:div.row
      [:div.column.override-dark
       [:> MaterialTable
        {:title "Labels"
         :components {:Toolbar (fn []
                                 (r/as-element
                                  [:div.ui.attached.stackable
                                   {:style {:padding "5px 10px"}}
                                   [:h3 "Labels"
                                    [:select.ui.dropdown.MuiTableDropdown
                                     {:style {:margin-left "5px"}
                                      :value (:status label-filters)
                                      :on-change (fn [ev]
                                                   (dispatch [::set [:label-filters :status] (aget ev "target" "value")]))}
                                     [:option {:value "enabled"} "Active"]
                                     [:option {:value "all"} "All"]
                                     [:option {:value "disabled"} "Disabled"]]]]))}
         :columns (clj->js cols)
         :parentChildData (fn [row rows]
                            (.find ^js rows #(= (.-id ^js %) (.-parentId ^js row))))
         :data (clj->js (sort-by :ordering-display-2 rows))
         :actions (when self-email
                    [(fn [rowData]
                       (clj->js
                        {:icon "share"
                         :disabled (some? (.-parentId ^js rowData))
                         :tooltip "Share label"
                         :onClick (fn [event rowData]
                                    (.stopPropagation event)
                                    (dispatch [:action [:labels/get-share-code project-id (.-id ^js rowData)]]))}))
                     (fn [rowData]
                       (if (.-enabled ^js rowData)
                         (clj->js
                          {:icon "block"
                           :tooltip "Disable label"
                           :disabled (or (= (.-name ^js rowData) "overall include")
                                         (= (.-valueType ^js rowData) "group"))
                           :onClick (fn [event rowData]
                                      (.stopPropagation event)
                                      (reset-local-label! labels-atom (or (.-parentId ^js rowData) "na") (.-id ^js rowData))
                                      (swap! (r/cursor labels-atom [(.-id ^js rowData) :enabled]) not)
                                      (sync-to-server))})
                         (clj->js
                          {:icon "check_circle"
                           :tooltip "Enable label"
                           :disabled (= (.-valueType ^js rowData) "group")
                           :onClick (fn [event rowData]
                                      (.stopPropagation event)
                                      (reset-local-label! labels-atom (or (.-parentId ^js rowData) "na") (.-id ^js rowData))
                                      (swap! (r/cursor labels-atom [(.-id ^js rowData) :enabled]) not)
                                      (sync-to-server))})))])
         :onRowClick (fn [_event rowData]
                       (when self-email
                         (dispatch [::set :editing-root-label-id (.-parentId ^js rowData)])
                         (dispatch [::set :editing-label-id (.-id ^js rowData)])))
         :options {:actionsColumnIndex -1}}]]
      [SideEditableView labels-atom]]]))

(defn- Panel []
  (let [admin? @(subscribe [:member/admin? true])
        labels (r/cursor state [:labels])
        read-only-message-closed? (r/cursor state [:read-only-message-closed?])
        project-id    @(subscribe [:active-project-id])
        project-plan  @(subscribe [:project/plan project-id])
        self-email @(subscribe [:self/email])
        group-labels-allowed? (or (and self-email (re-matches #".*@insilica.co" self-email))
                                  (plans-info/pro? project-plan))]
    (ensure-state)
    [:div.define-labels
     [ReadOnlyMessage
      "Editing label definitions is restricted to project administrators."
      read-only-message-closed?]
     [LabelsTable labels]
     (when admin?
       [:div {:style {:margin-top "1rem"}}
        (cond
          ;; Hide buttons when editing a label
          ;; See https://github.com/insilica/systematic_review/issues/21
          @(subscribe [::editing-label])
          [:<>]

          group-labels-allowed?
          [:div.ui.four.column.stackable.grid
           [:div.column [AddLabelButton "boolean" add-new-label!]]
           [:div.column [AddLabelButton "categorical" add-new-label!]]
           [:div.column [AddLabelButton "string" add-new-label!]]
           [:div.column [AddLabelButton "group" add-new-label!]]
           [:div.column [AddLabelButton "annotation" add-new-label!]]
           [:div.column [ImportLabelButton]]]

          :else
          [:div.ui.three.column.stackable.grid
           [:div.column [AddLabelButton "boolean" add-new-label!]]
           [:div.column [AddLabelButton "categorical" add-new-label!]]
           [:div.column [AddLabelButton "string" add-new-label!]]
           [:div.column [AddLabelButton "annotation" add-new-label!]]
           [:div.column [ImportLabelButton]]])
        (when-not group-labels-allowed?
          [UpgradeMessage])])
     [ShareLabelButton]]))

(def-panel :project? true :panel panel
  :uri "/labels/edit" :params [project-id] :name labels-edit
  :on-route (do (data/reload :project project-id)
                (dispatch [:set-active-panel panel])
                (dispatch [::set :label-filters {:status "enabled"}]))
  :content (fn [child] [Panel child]))

;; this wraps the panel for [:project :project :labels :edit]
(defmethod panel-content [:project :project :labels] []
  (fn [child]
    [:div.project-content
     (when-let [project-id @(subscribe [:active-project-id])]
       (when @(subscribe [:have? [:project project-id]])
         child))]))
