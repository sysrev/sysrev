(ns sysrev.views.annotator
  (:require ["jquery" :as $]
            [cljs-time.coerce :as tc]
            [goog.dom :as gdom]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.ui :as ui-state]
            [sysrev.views.components.core :as ui]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer
             [map-values filter-values css index-by]]))

(def view :annotator)

(defn- set-field [db context path value]
  (ui-state/set-view-field db view (concat [context] path) value))
(defn- update-field [db context path f]
  (ui-state/update-view-field db view (concat [context] path) f))

(reg-sub ::get
         (fn [[_ context path]]
           (subscribe [:view-field view (concat [context] path)]))
         identity)

(reg-event-db ::set [trim-v]
              (fn [db [context path value]]
                (set-field db context path value)))

(reg-event-db ::clear-annotations [trim-v]
              (fn [db [context]]
                (-> (set-field db context [:annotations] {})
                    (set-field context [:new-annotation] nil)
                    (set-field context [:editing-id] nil))))

(reg-event-db ::remove-ann [trim-v]
              (fn [db [context ann-id]]
                (update-field db context [:annotations] #(dissoc % ann-id))))

(reg-event-db :annotator/init-view-state [trim-v]
              (fn [db [context & [_panel]]]
                (set-field db context [] {:context context})))

(defn annotator-data-item
  "Given an annotator context, returns a vector representing both the
   def-data item and the re-frame subscription for the annotation data."
  [{:keys [class project-id article-id pdf-key] :as _context}]
  (case class
    "abstract" [:annotator/article project-id article-id]
    "pdf"      [:annotator/article-pdf project-id article-id pdf-key]
    nil))

(def-action :annotator/create-annotation
  :uri (fn [] "/api/annotation/create")
  :content (fn [context annotation-map]
             {:project-id (:project-id context)
              :context context
              :annotation-map annotation-map})
  :process (fn [_ [context _annotation-map] {:keys [annotation-id]}]
             {:dispatch-n
              (list [:data/after-load (annotator-data-item context) ::clear-on-create
                     [::clear-annotations context]]
                    [:reload (annotator-data-item context)])}))

(def-action :annotator/update-annotation
  :uri (fn [_ annotation-id _ _] (str "/api/annotation/update/" annotation-id))
  :content (fn [context _ annotation semantic-class]
             {:project-id (:project-id context)
              :annotation annotation
              :semantic-class semantic-class})
  :process (fn [_ [context _ _ _] _]
             {:dispatch-n (list [:reload (annotator-data-item context)]
                                [::set context [:editing-id] nil])}))

(def-action :annotator/delete-annotation
  :uri (fn [_ annotation-id]
         (str "/api/annotation/delete/" annotation-id))
  :content (fn [context _] {:project-id (:project-id context)})
  :process (fn [_ [context annotation-id] _]
             {:dispatch-n (list [::remove-ann context annotation-id]
                                [:reload (annotator-data-item context)])}))

(def-data :annotator/status
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id :annotator])
                 (contains? :status)))
  :uri (fn [_] "/api/annotation/status")
  :prereqs (fn [project-id] [[:project project-id]])
  :content (fn [project-id] {:project-id project-id})
  :process (fn [{:keys [db]} [project-id] {:keys [status]}]
             {:db (assoc-in db [:data :project project-id :annotator :status] status)}))

(reg-sub :annotator/status
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(-> % :annotator :status))

(defn- sort-class-options [entries]
  (vec (->> entries
            (map-values (fn [x] (update x :last-used #(some-> % tc/to-long))))
            (sort-by (fn [[_class {:keys [last-used count]}]]
                       [(or last-used 0) count]))
            reverse)))

(reg-sub :annotator/semantic-class-options
         (fn [[_ project-id]] (subscribe [:annotator/status project-id]))
         (fn [{:keys [member project]}]
           (when (or member project)
             (let [member-sorted (when member
                                   (->> (sort-class-options member)
                                        (filterv (fn [[_class {:keys [last-used count]}]]
                                                   (and last-used (not= 0 last-used)
                                                        count (not= 0 count))))))
                   project-sorted (when project (sort-class-options project))]
               (->> (concat member-sorted project-sorted)
                    (mapv first) distinct vec)))))

(def-data :annotator/article
  :loaded? (fn [db project-id article-id]
             (-> (get-in db [:data :project project-id :annotator :article])
                 (contains? article-id)))
  :uri (fn [_ article-id]
         (str "/api/annotations/user-defined/" article-id))
  :prereqs (fn [project-id article-id]
             [[:project project-id] [:article project-id article-id]])
  :content (fn [project-id _] {:project-id project-id})
  :process (fn [{:keys [db]} [project-id article-id] {:keys [annotations]}]
             (when annotations
               {:db (assoc-in db [:data :project project-id :annotator :article article-id]
                              (index-by :annotation-id annotations))
                :dispatch [:reload [:annotator/status project-id]]})))

(reg-sub :annotator/article
         (fn [db [_ project-id article-id]]
           (get-in db [:data :project project-id :annotator :article article-id])))

(def-data :annotator/article-pdf
  :loaded? (fn [db project-id article-id pdf-key]
             (-> (get-in db [:data :project project-id :annotator :article-pdf article-id])
                 (contains? pdf-key)))
  :uri (fn [_ article-id pdf-key]
         (str "/api/annotations/user-defined/" article-id "/pdf/" pdf-key))
  :prereqs (fn [project-id article-id _]
             [[:project project-id] [:article project-id article-id]])
  :content (fn [project-id _ _] {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id article-id pdf-key] {:keys [annotations]}]
    (when annotations
      {:db (assoc-in db [:data :project project-id :annotator :article-pdf article-id pdf-key]
                     (index-by :annotation-id annotations))
       :dispatch [:reload [:annotator/status project-id]]})))

(reg-sub :annotator/article-pdf
         (fn [db [_ project-id article-id pdf-key]]
           (get-in db [:data :project project-id
                       :annotator :article-pdf article-id pdf-key])))

(defn AnnotationEditor [context ann-id]
  (let [set (fn [path value] (dispatch-sync [::set context path value]))
        set-ann (fn [path value] (set (concat [:annotations ann-id] path) value))
        {:keys [new-annotation editing-id]} @(subscribe [::get context])
        data @(subscribe (annotator-data-item context))
        saved (get data ann-id)
        new? (empty? saved)
        initial-new (when new? new-annotation)
        selection (:selection (if new? initial-new saved))
        editing? (or (= ann-id editing-id)
                     (and new? (nil? editing-id)))
        self-id @(subscribe [:self/user-id])
        user-id (if new? self-id (:user-id saved))
        self? (= user-id self-id)
        fields [:annotation :semantic-class]
        annotation @(subscribe [::get context [:annotations ann-id]])
        class-options @(subscribe [:annotator/semantic-class-options])
        active (if new?
                 {:selection (:selection initial-new)
                  :annotation (or (:annotation annotation)
                                  (:annotation initial-new))
                  :semantic-class (or (:semantic-class annotation)
                                      (:semantic-class initial-new)
                                      (first class-options))
                  :context (:context initial-new)}
                 {:selection (:selection saved)
                  :annotation (or (:annotation annotation)
                                  (:annotation saved))
                  :semantic-class (or (:semantic-class annotation)
                                      (:semantic-class saved))})
        changed? (if new?
                   (not= (select-keys active fields)
                         (select-keys initial-new fields))
                   (not= (select-keys active fields)
                         (select-keys saved fields)))
        on-save #(if new?
                   (dispatch [:action [:annotator/create-annotation
                                       context active]])
                   (dispatch [:action [:annotator/update-annotation
                                       context ann-id
                                       (:annotation active)
                                       (:semantic-class active)]]))
        on-delete #(dispatch [:action [:annotator/delete-annotation context ann-id]])
        full-width? (>= (util/viewport-width) 1340)
        button-class (css "ui fluid tiny" [full-width? "labeled"] "icon button")
        touchscreen? @(subscribe [:touchscreen?])]
    [:div.ui.secondary.segment.annotation-view
     {:class (css [new? "new-annotation"])
      :data-annotation-id (str ann-id)
      :data-semantic-class (:semantic-class saved)
      :data-annotation (:annotation saved)}
     [:form.ui.small.form.edit-annotation
      {:on-submit (util/wrap-user-event on-save :prevent-default true)}
      [:div.field.selection
       [:label "Selection"]
       [:div.ui.small.basic.label.selection-label
        {:class (css [new? "new-annotation"])}
        (pr-str (str (when (string? selection)
                       (sutil/string-ellipsis
                        selection 400 (str nbsp nbsp nbsp "[..........]" nbsp nbsp nbsp)))))]]
      [:div.field.semantic-class
       [:label "Semantic Class"]
       (let [{:keys [new-class]} annotation
             toggle-new-class #(do (set-ann [:new-class] (not new-class))
                                   (set-ann [:semantic-class] nil))
             text-input? (or (not editing?) (empty? class-options) new-class)]
         [:div.fields
          (if text-input?
            [ui/TextInput {:value (if editing?
                                    (:semantic-class annotation)
                                    (or (:semantic-class annotation)
                                        (:semantic-class saved)))
                           :on-change (util/on-event-value #(set-ann [:semantic-class] %))
                           :read-only (not editing?)
                           :disabled (not editing?)
                           :placeholder (when editing? "New class name")}]
            [ui/selection-dropdown
             [:div.text (:semantic-class active)]
             (map-indexed (fn [i class]
                            [:div.item
                             {:key [:semantic-class-option i]
                              :data-value (str class)
                              :class (css [(= class (:semantic-class active)) "active selected"])}
                             [:span (str class)]])
                          class-options)
             {:class (css "ui fluid" [(not touchscreen?) "search"] "selection dropdown"
                          [(not editing?) "disabled"])
              :onChange (fn [v _t] (set-ann [:semantic-class] v))}])
          [:div.ui.tiny.icon.button.new-semantic-class
           {:class (css [(or (not editing?) (empty? class-options)) "disabled"])
            :on-click (util/wrap-user-event toggle-new-class)}
           [:i {:class (css [text-input? "list ul" :else "plus"] "icon")}]]])]
      [:div.field.value
       [:label "Value"]
       [ui/TextInput {:value (:annotation active)
                      :on-change (util/on-event-value #(set-ann [:annotation] %))
                      :read-only (not editing?)
                      :disabled (not editing?)}]]
      (cond editing? [:div.field.buttons>div.fields
                      [:div.eight.wide.field
                       [:button {:type "submit"
                                 :class (css button-class "positive" "save-annotation"
                                             [(and (not changed?) (not new?)) "disabled"])}
                        [:i.check.circle.outline.icon]
                        (when full-width? "Save")]]
                      [:div.eight.wide.field
                       [:button {:class (css button-class "cancel-edit")
                                 :on-click (util/wrap-user-event
                                            #(do (set [:editing-id] nil)
                                                 (dispatch-sync [::clear-annotations context]))
                                            :prevent-default true)}
                        [:i.times.icon]
                        (when full-width? "Cancel")]]]
            self?    [:div.field.buttons>div.fields
                      [:div.eight.wide.field
                       [:button {:class (css button-class "edit-annotation")
                                 :on-click (util/wrap-user-event
                                            #(do (dispatch-sync [::clear-annotations context])
                                                 (set [:editing-id] ann-id))
                                            :prevent-default true)}
                        [:i.blue.pencil.alternate.icon]
                        (when full-width? "Edit")]]
                      [:div.eight.wide.field
                       [:button {:class (css button-class "delete-annotation")
                                 :on-click (util/wrap-user-event on-delete :prevent-default true)}
                        [:i.red.circle.times.icon]
                        (when full-width? "Delete")]]])]]))

(defn AddAnnotation
  "Render absolute-positioned \"Add Annotation\" button
  within AnnotationCapture container."
  [{:keys [class] :as context}]
  (when (seq @(subscribe [::get context [:selection]]))
    (let [pdf? (= class "pdf")
          {:keys [new-annotation selection positions]} @(subscribe [::get context])
          {:keys [client-x client-y ctarget-x ctarget-y scroll-x scroll-y]} positions
          dark-theme? @(subscribe [:self/dark-theme?])
          on-save (fn []
                    (dispatch-sync [::set context [:selection] ""])
                    (util/clear-text-selection)
                    (dispatch [:action [:annotator/create-annotation context new-annotation]]))
          [x y] [;; position values all recorded from the latest mouse selection event
                 (+
                  ;; mouse position from selection event
                  client-x
                  ;; take the difference from position of `div.annotation-capture`
                  ;; (direct parent element of this)
                  (- ctarget-x)
                  ;; add the window scroll offset
                  ;;
                  ;; (except don't for PDF, because this is all positioned inside
                  ;;  a Semantic modal, which has taken over scrolling for all
                  ;;  the content inside it.
                  ;;
                  ;;  apparently the `clientX` and `get-element-position` calculations
                  ;;  already account for the scrolling in this context.)
                  (if pdf? 0 scroll-x)
                  ;; subtract ~half the width of the popup element for centering
                  (- 60))
                 ;; y calculations same as x
                 (+
                  client-y
                  (- ctarget-y)
                  (if pdf? 0 scroll-y)
                  ;; shift the popup element slightly above the selection
                  (- 50))]]
      [:div.ui.button.add-annotation-popup
       {:class (css [(and dark-theme? (not pdf?)) "dark-theme" :else "secondary"])
        :style {:top (str y "px")
                :left (str x "px")
                :display (when (empty? selection) "none")}
        :on-click (util/wrap-user-event on-save
                                        :stop-propagation true
                                        :prevent-default true)}
       "Add Annotation"])))

;; Returns map of all annotations for context.
;; Includes new-annotation entry when include-new is true.
(reg-sub :annotator/all-annotations
         (fn [[_ context _]]
           [(subscribe [::get context [:new-annotation]])
            (subscribe (annotator-data-item context))])
         (fn [[new-annotation annotations]
              [_ _ include-new]]
           (cond->> annotations
             (and new-annotation include-new)
             (merge {(:annotation-id new-annotation) new-annotation}))))

;; Returns map of annotations for context filtered by user-id.
;; Includes new-annotation entry for self-id unless only-saved is true.
(reg-sub :annotator/user-annotations
         (fn [[_ context _ & _]]
           [(subscribe [:self/user-id])
            (subscribe [::get context [:new-annotation]])
            (subscribe (annotator-data-item context))])
         (fn [[self-id new-annotation annotations]
              [_ _ user-id & [{:keys [only-saved]}]]]
           (cond->> (filter-values #(= (:user-id %) user-id) annotations)
             (and new-annotation (= user-id self-id) (not only-saved))
             (merge {(:annotation-id new-annotation) new-annotation}))))

(defn AnnotationMenu
  "Full component for editing and viewing annotations."
  [context class]
  (dispatch [:require [:annotator/status (:project-id context)]])
  (dispatch [:require (annotator-data-item context)])
  (let [self-id @(subscribe [:self/user-id])
        {:keys [new-annotation]} @(subscribe [::get context])
        annotations @(subscribe [:annotator/user-annotations context self-id
                                 {:only-saved true}])]
    [:div.ui.segments.annotation-menu {:class class}
     [:div.ui.center.aligned.secondary.segment.menu-header
      [:div.ui.large.fluid.label "Select text to annotate"]]
     (when-let [new-id (:annotation-id new-annotation)]
       [AnnotationEditor context new-id])
     (doall
      (for [ann-id (->> (keys annotations) (filter integer?) sort reverse)] ^{:key ann-id}
        [AnnotationEditor context ann-id]))]))

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
        touchscreen? @(subscribe [:touchscreen?])
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
                (set [:new-annotation] nil)
                (let [entry {:annotation-id (str "new-ann-" (sutil/random-id))
                             :selection (:selection selection-map)
                             :context (-> (dissoc selection-map :selection)
                                          (assoc :client-field field))
                             :annotation ""
                             :semantic-class nil}]
                  (set [:new-annotation] entry)
                  (set-ann (:annotation-id entry) nil entry)
                  (when (not touchscreen?)
                    (-> #($/focus ($ ".annotation-view.new-annotation .field.value input"))
                        (js/setTimeout 50))))))
            true))]
    [:div.annotation-capture {:on-mouse-up update-selection
                              :on-touch-end update-selection}
     #_ [AddAnnotation context]
     child]))
