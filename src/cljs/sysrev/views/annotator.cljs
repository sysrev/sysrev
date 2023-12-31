(ns sysrev.views.annotator
  (:require [clojure.data.xml :as dxml]
            [goog.dom :as gdom]
            [medley.core :as medley]
            [sysrev.views.semantic :as S]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db trim-v reg-event-fx]]
            [sysrev.state.ui :as ui-state]
            [sysrev.views.components.core :as ui]
            [sysrev.views.components.pdfjs-express :as pdfjs-express]
            ["react-hotkeys-hook" :refer [useHotkeys]]
            [sysrev.util :as util :refer [css nbsp]]))

(def view :annotator)

(def draft-ann-id -1)

(defn- set-field [db context path value]
  (ui-state/set-view-field db view (concat [context] path) value))
(defn- update-field [db context path f]
  (ui-state/update-view-field db view (concat [context] path) f))

(reg-sub ::get
         (fn [[_ context path]]
           (subscribe [:view-field view (concat [context] path)]))
         identity)

(reg-sub ::annotation-label-data
         (fn [db _]
           (get-in db [:state :annotation-label])))

(reg-event-db ::set [trim-v]
              (fn [db [context path value]]
                (set-field db context path value)))

(reg-event-db ::remove-ann [trim-v]
              (fn [db [context ann-id]]
                (update-field db context [:annotations] #(dissoc % ann-id))))

(reg-event-db ::cancel-new-ann [trim-v]
              (fn [db [context]]
                (-> (set-field db context [:editing-id] nil)
                    (set-field context [:selection] nil)
                    (update-field context [:annotations] #(dissoc % draft-ann-id)))))

(reg-event-db ::save-draft-ann [trim-v]
              (fn [db [context]]
                (update-field db context [:annotations]
                              (fn [annotations]
                                (let [new-id (str "ann-" (util/random-id))
                                      annotation (-> (get annotations draft-ann-id)
                                                     (assoc :annotation-id new-id))
                                      ret (-> annotations
                                              (dissoc draft-ann-id)
                                              (assoc new-id annotation))]
                                  ret)))))

(reg-event-db :reset-annotations [trim-v]
              (fn [db [context article-id annotations]]
                (let [abstract @(subscribe [:article/abstract article-id])]
                  (set-field db context [:annotations]
                             (->> (or annotations {})
                                  (map (fn [[ann-id annotation]]
                                         [ann-id (update annotation :context assoc :text-context abstract)]))
                                  (into {}))))))

(defn editor-hotkeys-listener [{:keys [values callback]}]
  (fn []
    (doall
     (map-indexed
      (fn [idx v]
        (useHotkeys (str "shift+" (inc idx)) #(callback idx v)))
      values))
    [:<>]))

(defn AnnotationEditor [context ann-id annotation]
  (let [{:keys [root-label-id label-id]} @(subscribe [::annotation-label-data])
        set (fn [path value]
              (dispatch-sync [::set context path value]))
        set-ann (fn [path value] (set (concat [:annotations ann-id] path) value))
        {:keys [editing-id]} @(subscribe [::get context])
        editing? (= ann-id editing-id)
        all-values @(subscribe [:label/all-values root-label-id label-id])
        on-save (fn []
                  (when (= ann-id draft-ann-id)
                    (dispatch-sync [::save-draft-ann context]))
                  (set [:editing-id] nil)
                  (dispatch [::save context]))
        on-delete (fn []
                    (dispatch-sync [::remove-ann context ann-id])
                    (dispatch [::save context]))
        full-width? (>= (util/viewport-width) 1340)
        button-class (css "ui fluid tiny" [full-width? "labeled"] "icon button")
        touchscreen? @(subscribe [:touchscreen?])
        selection (:selection annotation)]
    [:div.ui.secondary.segment.annotation-view
     {:data-annotation-id (str ann-id)
      :data-semantic-class (:semantic-class annotation)
      :data-annotation (:annotation annotation)
      :class [(when (= ann-id draft-ann-id) "new-annotation")]}
     (when editing?
       [:f> editor-hotkeys-listener
        {:values all-values
         :callback (fn [_ v]
                     (set-ann [:semantic-class] v)
                     (set-ann [:value] selection)
                     (when (not touchscreen?)
                       (-> #(.select (js/document.querySelector ".annotation-view.new-annotation .field.value input"))
                           (js/setTimeout 50))))}])
     [:form.ui.small.form.edit-annotation
      {:on-submit (util/wrap-user-event on-save :prevent-default true)}
      [:div.field.selection
       [:label "Selection"]
       [:div.ui.small.basic.label.selection-label
        (pr-str (str (when (string? selection)
                       (util/ellipsis-middle
                        selection 400 (str nbsp nbsp nbsp "[..........]" nbsp nbsp nbsp)))))]]
      [:div.field.semantic-class
       [:label "Entity"]
       (let [text-input? (not editing?)]
         [:div.fields
          (if text-input?
            [ui/TextInput {:default-value (:semantic-class annotation)
                           :on-change (util/on-event-value #(set-ann [:semantic-class] %))
                           :read-only (not editing?)
                           :disabled (not editing?)
                           :placeholder (when editing? "New class name")}]
            [S/Dropdown {:selection true :fluid true
                         :options (map-indexed (fn [i v]
                                                 {:key [:value-option i]
                                                  :value v
                                                  :text v})
                                               all-values)
                         :on-change (fn [_ selected-option]
                                      (set-ann [:semantic-class] (.-value selected-option)))
                         :value (:semantic-class annotation)}])])]
      [:div.field.value
       [:label "Value"]
       [ui/TextInput {:default-value (:value annotation)
                      :on-change (util/on-event-value #(set-ann [:value] %))
                      :read-only (not editing?)
                      :disabled (not editing?)
                      :placeholder (when editing? "")}]]
      (if editing?
        [:div.field.buttons>div.fields
         [:div.eight.wide.field
          [:button {:type "submit"
                    :class (css button-class "positive" "save-annotation")}
           [:i.check.circle.outline.icon]
           (when full-width? "Save")]]
         [:div.eight.wide.field
          [:button {:class (css button-class "cancel-edit")
                    :on-click (util/wrap-user-event
                               #(dispatch-sync [::cancel-new-ann context])
                               :prevent-default true)}
           [:i.times.icon]
           (when full-width? "Cancel")]]]
        ;; not editing
        [:div.field.buttons>div.fields
         [:div.eight.wide.field
          [:button {:class (css button-class "edit-annotation")
                    :on-click (util/wrap-user-event
                               #(set [:editing-id] ann-id)
                               :prevent-default true)}
           [:i.blue.pencil.alternate.icon]
           (when full-width? "Edit")]]
         [:div.eight.wide.field
          [:button {:class (css button-class "delete-annotation")
                    :on-click (util/wrap-user-event on-delete :prevent-default true)}
           [:i.red.circle.times.icon]
           (when full-width? "Delete")]]])]]))

(reg-sub :annotator/label-annotations
         (fn [[_ context]]
           (subscribe [::get context [:annotations]]))
         (fn [m [_ _ ks]]
           (if (seq ks)
             (medley/map-vals #(select-keys % ks) m)
             m)))

(reg-event-fx ::save
              (fn [{:keys [db]} [_ context]]
                (let [annotation-label-data @(subscribe [::annotation-label-data])
                      annotations (->> @(subscribe [::get context [:annotations]])
                                       (map (fn [[ann-id annotation]]
                                              [ann-id (update annotation :context dissoc :text-context)]))
                                       (into {}))
                      article-id (:article-id context)
                      {:keys [root-label-id label-id ith]} annotation-label-data]
                  {:dispatch [:review/set-label-value article-id root-label-id label-id ith
                              (with-meta annotations {:force-merge-override true})]})))

(defn AnnotationMenu
  "Full component for editing and viewing annotations."
  [context class]
  (let [{:keys [root-label-id label-id _]} @(subscribe [::annotation-label-data])
        all-values @(subscribe [:label/all-values root-label-id label-id])
        label-name @(subscribe [:label/display root-label-id label-id])
        annotations @(subscribe [:annotator/label-annotations context])]
    [:div.ui.segments.annotation-menu {:class class}
     [:div.ui.center.aligned.secondary.segment.menu-header
      [:div.ui.large.fluid.label label-name]]
     [:div {:style {:margin "8px"}}
      "Select text to annotate. You can use the following key shortcuts:"]
     (doall
      (for [[idx v] (map-indexed vector all-values)] ^{:key idx}
           [:div.ui.secondary.basic.label {:style {:margin 5}}
            "shift+" (inc idx)
            [:span.detail v]]))
     (doall
      (for [[annotation-id annotation] (reverse annotations)] ^{:key annotation-id}
           [AnnotationEditor context annotation-id annotation]))]))

(defonce js-text-type (type (js/Text.)))

(defn previous-text
  "Get all previous text from nodes up until node with attribute
  data-field = field. Return result as a string"
  ([node field]
   (previous-text (gdom/getPreviousNode node) field ""))
  ([node field string]
   (cond
     ;; we need this, it's a text node
     (= (type node) js-text-type)
     (previous-text (gdom/getPreviousNode node) field
                    (str (.-data node) string))
     ;; we're at the final node
     (= (.getAttribute node "data-field") field)
     string
     ;; skip this node, it isn't a text node
     :else (previous-text (gdom/getPreviousNode node) field string))))

;; see: https://developer.mozilla.org/en-US/docs/Web/API/Selection
;;      https://developer.mozilla.org/en-US/docs/Web/API/Range
;;      https://developer.mozilla.org/en-US/docs/Web/API/Node
(defn get-selection
  "Get the current selection relative to text in the component with class"
  [field]
  (let [current-selection (.getSelection js/window)
        range (.getRangeAt current-selection 0)
        common-ancestor (.-commonAncestorContainer range)]
    (when (and (> (.-rangeCount current-selection) 0)
               ;; when annotations are overlapped, below is nil (undefined)
               (.-data common-ancestor))
      (let [previous-text (previous-text common-ancestor field)
            root-text (-> (gdom/getAncestor common-ancestor
                                            #(= (.getAttribute % "data-field") field))
                          (gdom/getRawTextContent))
            current-selection (.toString current-selection)
            start-offset (+ (.-startOffset range) (count previous-text))
            end-offset (+ start-offset (count current-selection))]
        {:selection (.toString current-selection)
         :text-context root-text
         :start-offset start-offset
         :end-offset end-offset}))))

(defn AnnotationCapture
  "Create an Annotator using state. A child is a single reagent
  component which has text to be captured. field corresponds to the
  article column on the server and the data-field attribute of the
  root node."
  [context field child]
  (let [set (fn [path value] (dispatch-sync [::set context path value]))
        set-ann (fn [ann-id path value] (set (concat [:annotations ann-id] path) value))
        set-pos (fn [key value] (set [:positions key] value))
        update-selection
        (when @(subscribe [:review-interface])
          (fn [e]
            (when false
              (let [ctarget (.-currentTarget e)
                    {ct-x :left ct-y :top} (util/get-element-position ctarget)
                    {sc-x :left sc-y :top} (util/get-scroll-position)
                    c-x (.-clientX e)
                    c-y (.-clientY e)]
                (set-pos :scroll-x sc-x)
                (set-pos :scroll-y sc-y)
                (set-pos :ctarget-x ct-x)
                (set-pos :ctarget-y ct-y)
                (set-pos :client-x c-x)
                (set-pos :client-y c-y)))
            (let [selection-map (get-selection field)]
              (set [:selection] (:selection selection-map))
              (if (empty? (:selection selection-map))
                (dispatch-sync [::cancel-new-ann context])
                (let [entry {:annotation-id draft-ann-id ;(str "ann-" (util/random-id))
                             :selection (:selection selection-map)
                             :context (-> (dissoc selection-map :selection)
                                          (assoc :client-field field))
                             :annotation ""
                             :value (:selection selection-map)
                             :semantic-class nil}]
                  (set [:editing-id] draft-ann-id)
                  (set-ann draft-ann-id nil entry))))
            true))]
    [:div.annotation-capture {:on-mouse-up update-selection
                              :on-touch-end update-selection}
     child]))

(defn clean-up-xfdf-annotation
  "Remove redundant data like the selection content and name (same as id)."
  [ann]
  (-> (update ann :attrs dissoc :name)
      (update :content (partial remove (comp #{:xmlns.http%3A%2F%2Fns.adobe.com%2Fxfdf%2F/contents} :tag)))))

(defn parse-xfdf-annotations
  "Parses an XML document containing XFDF annotations and returns a seq of
  annotation maps. Removes redundant data via `clean-up-xfdf-annotation`."
  [xml-str]
  (->> (dxml/parse-str xml-str)
       :content first :content first
       clean-up-xfdf-annotation))


(defn annotation-changed-fn [annotation-context document-id]
  (fn [^js viewer annotations action]
    (cond
      (#{"add" "modify"} action)
      (let [^js ann-mgr (.-annotationManager ^js (.-Core viewer))]
        (doseq [^js a annotations
                :let [contents (.getContents a)
                      id (uuid (.-Id a))
                      subject (.-Subject a)]]
          (when (or (seq contents) (= "Rectangle" subject)) ; Ignore spurious blank annotations
            (.then
             (.exportAnnotations ann-mgr #js{:annotList #js[a]})
             (fn [xml-str]
               (dispatch-sync [::set annotation-context [:editing-id] id])
               (dispatch-sync [::set annotation-context
                               [:annotations id]
                               {:annotation-id id
                                :document-id document-id
                                :selection contents
                                :value contents
                                :xfdf (parse-xfdf-annotations xml-str)}]))))))

      (= "delete" action)
      (doseq [^js a annotations]
        (dispatch-sync [::remove-ann annotation-context (uuid (.-Id a))])))))

(defn AnnotatingPDFViewer [{:keys [annotation-context document-id read-only?] :as opts}]
  [pdfjs-express/Viewer
   (merge
    {:disabled-elements
     (into
      ["freeHandHighlightToolButton"
       "freeHandHighlightToolGroupButton"
       "freeHandToolButton"
       "freeHandToolGroupButton"
       "freeTextToolButton"
       "freeTextToolGroupButton"
       "linkButton"
       "squigglyToolGroupButton"
       "stickyToolButton"
       "stickyToolGroupButton"
       "strikeoutToolGroupButton"
       "textStrikeoutToolButton"
       "textSquigglyToolButton"
       "textUnderlineToolButton"
       "themeChangeButton"
       "toggleNotesButton"
       "toolbarGroup-FillAndSign"
       "toolbarGroup-Insert"
       "toolbarGroup-Shapes"
       "underlineToolGroupButton"]
      (when read-only?
        ["annotationPopup"
         "highlightToolButton"
         "ribbons"
         "textHighlightToolButton"
         "toolsHeader"]))
     :features (conj pdfjs-express/default-features "Annotations")
     :on-annotation-changed
     (annotation-changed-fn annotation-context document-id)
     :read-only read-only?}
    opts)])
