(ns sysrev.views.annotator
  (:require [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.loading :as loading]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.ui :as ui-state]
            [sysrev.views.components :as ui]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in? map-values]])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [with-loader]]))

(def view :annotator)

(def initial-annotator-state {})

(reg-sub
 ::get
 (fn [[_ context path]]
   [(subscribe [:view-field view [context]])])
 (fn [[context-state] [_ _ path]]
   (get-in context-state path)))

(reg-event-db
 ::set
 [trim-v]
 (fn [db [context path value]]
   (ui-state/set-view-field
    db view (concat [context] path) value)))

(reg-sub
 ::annotations
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[context-state]] (:annotations context-state)))

(reg-sub
 ::get-ann
 (fn [[_ context id path]]
   [(subscribe [:view-field view [context :annotations id]])])
 (fn [[ann-state] [_ _ _ path]]
   (get-in ann-state path)))

(reg-event-db
 ::set-ann
 [trim-v]
 (fn [db [context id path value]]
   (ui-state/set-view-field
    db view (concat [context :annotations id] path) value)))

(reg-event-db
 ::clear-annotations
 [trim-v]
 (fn [db [context]]
   (-> db
       (ui-state/set-view-field
        view [context :annotations] {})
       (ui-state/set-view-field
        view [context :new-annotation] nil)
       (ui-state/set-view-field
        view [context :editing-id] nil))))

(reg-event-db
 ::remove-ann
 [trim-v]
 (fn [db [context id]]
   (let [path [context :annotations]
         annotations (ui-state/get-view-field db path view)]
     (ui-state/set-view-field db view path
                              (dissoc annotations id)))))

(reg-sub
 :annotator/enabled
 (fn [db [_ {:keys [class] :as context}]]
   (boolean (get-in db [:state :annotator class :enabled]))))

(reg-event-db
 :annotator/enabled
 (fn [db [_ {:keys [class] :as context} enabled?]]
   (assoc-in db [:state :annotator class :enabled]
             (boolean enabled?))))

(reg-event-db
 :annotator/clear-view-state
 [trim-v]
 (fn [db [panel]]
   (ui-state/set-view-field db view nil {} panel)))

(reg-event-db
 :annotator/init-view-state
 [trim-v]
 (fn [db [context & [panel]]]
   (ui-state/set-view-field
    db view [context]
    (merge initial-annotator-state {:context context}))))

(defn annotator-data-item
  "Given an annotator context, returns a vector representing both the
   def-data item and the re-frame subscription for the annotation data."
  [{:keys [class project-id article-id pdf-key] :as context}]
  (case class
    "abstract" [:annotator/article project-id article-id]
    "pdf"      [:annotator/article-pdf project-id article-id pdf-key]
    nil))

(reg-sub-raw
 :annotator/data
 (fn [db [_ context]]
   (reaction
    (let [subscription (annotator-data-item context)]
      @(subscribe subscription)))))

(def-action :annotator/create-annotation
  :uri (fn [] "/api/annotation/create")
  :content (fn [context annotation-map]
             {:project-id (:project-id context)
              :context context
              :annotation-map annotation-map})
  :process (fn [_ [context annotation-map] {:keys [annotation-id]}]
             {:dispatch-n
              (list [:data/after-load
                     (annotator-data-item context)
                     ::clear-on-create
                     [::clear-annotations context]]
                    [:reload (annotator-data-item context)])}))

(def-action :annotator/update-annotation
  :uri (fn [_ annotation-id _ _] (str "/api/annotation/update/" annotation-id))
  :content (fn [context _ annotation semantic-class]
             {:project-id (:project-id context)
              :annotation annotation
              :semantic-class semantic-class})
  :process (fn [_ [context annotation-id _ _] result]
             {:dispatch-n
              (list [:reload (annotator-data-item context)]
                    [::set context [:editing-id] nil])}))

(def-action :annotator/delete-annotation
  :uri (fn [_ annotation-id]
         (str "/api/annotation/delete/" annotation-id))
  ;; :content (fn [annotation-id] annotation-id)
  :content (fn [context _]
             {:project-id (:project-id context)})
  :process (fn [_ [context annotation-id] result]
             {:dispatch-n
              (list [::remove-ann context annotation-id]
                    [:reload (annotator-data-item context)])}))

(def-data :annotator/status
  :loaded? (fn [db project-id]
             (-> (get-in db [:data :project project-id :annotator])
                 (contains? :status)))
  :uri (fn [_] "/api/annotation/status")
  :prereqs (fn [project-id]
             [[:identity]
              [:project project-id]])
  :content (fn [project-id]
             {:project-id project-id})
  :process (fn [{:keys [db]} [project-id] {:keys [status]}]
             {:db (assoc-in db [:data :project project-id :annotator :status]
                            status)}))

(reg-sub
 :annotator/status
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (-> project :annotator :status)))

(defn- sort-class-options [entries]
  (->> entries
       (map-values
        (fn [fields]
          (update fields :last-used
                  #(when % (tc/to-long %)))))
       (sort-by
        (fn [[class {:keys [last-used count]}]]
          [(if last-used last-used 0)
           count]))
       reverse vec))

(reg-sub
 :annotator/semantic-class-options
 (fn [[_ project-id]]
   [(subscribe [:annotator/status project-id])])
 (fn [[{:keys [member project]}]]
   (when (or member project)
     (let [member-sorted (when member
                           (->> (sort-class-options member)
                                (filterv
                                 (fn [[class {:keys [last-used count]}]]
                                   (and last-used (not= 0 last-used)
                                        count (not= 0 count))))))
           project-sorted (when project
                            (sort-class-options project))]
       (->> (concat member-sorted project-sorted)
            (mapv first) distinct vec)))))

(def-data :annotator/article
  :loaded? (fn [db project-id article-id]
             (-> (get-in db [:data :project project-id
                             :annotator :article])
                 (contains? article-id)))
  :uri (fn [_ article-id]
         (str "/api/annotations/user-defined/" article-id))
  :prereqs (fn [project-id article-id]
             [[:identity]
              [:project project-id]
              [:article project-id article-id]])
  :content (fn [project-id _]
             {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id article-id] {:keys [annotations]}]
    (if annotations
      {:db (-> db
               (assoc-in [:data :project project-id
                          :annotator :article article-id]
                         (util/vector->hash-map annotations :id)))
       :dispatch [:reload [:annotator/status project-id]]}
      {})))

(reg-sub
 :annotator/article
 (fn [db [_ project-id article-id]]
   (get-in db [:data :project project-id
               :annotator :article article-id])))

(def-data :annotator/article-pdf
  :loaded? (fn [db project-id article-id pdf-key]
             (-> (get-in db [:data :project project-id
                             :annotator :article-pdf article-id])
                 (contains? pdf-key)))
  :uri (fn [_ article-id pdf-key]
         (str "/api/annotations/user-defined/" article-id
              "/pdf/" pdf-key))
  :prereqs (fn [project-id article-id _]
             [[:identity]
              [:project project-id]
              [:article project-id article-id]])
  :content (fn [project-id _ _]
             {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id article-id pdf-key] {:keys [annotations]}]
    (if annotations
      {:db (-> db
               (assoc-in [:data :project project-id
                          :annotator :article-pdf article-id pdf-key]
                         (util/vector->hash-map annotations :id)))
       :dispatch [:reload [:annotator/status project-id]]}
      {})))

(reg-sub
 :annotator/article-pdf
 (fn [db [_ project-id article-id pdf-key]]
   (get-in db [:data :project project-id
               :annotator :article-pdf article-id pdf-key])))

(defn AnnotationEditor
  [context id]
  (let [set (fn [path value]
              (dispatch-sync [::set context path value]))
        set-ann (fn [path value]
                  (dispatch-sync [::set-ann context id path value]))
        {:keys [new-annotation editing-id]} @(subscribe [::get context])
        data @(subscribe (annotator-data-item context))
        saved (get data id)
        new? (empty? saved)
        initial-new (when new? new-annotation)
        selection (if new?
                    (:selection initial-new)
                    (:selection saved))
        editing? (or (= id editing-id)
                     (and new? (nil? editing-id)))
        current-user @(subscribe [:self/user-id])
        original-user-id (if new? current-user (:user-id saved))
        fields [:annotation :semantic-class]
        annotation (get @(subscribe [::annotations context]) id)
        class-options @(subscribe [:annotator/semantic-class-options])
        active (if new?
                 {:selection (:selection initial-new)
                  :annotation (or (:annotation annotation)
                                  (:annotation initial-new))
                  :semantic-class (or (:semantic-class annotation)
                                      (:semantic-class initial-new)
                                      (first class-options))}
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
                                       context id
                                       (:annotation active)
                                       (:semantic-class active)]]))
        on-delete #(dispatch [:action [:annotator/delete-annotation
                                       context id]])
        full-width? (>= (util/viewport-width) 1340)
        button-class
        (if full-width?
          "ui fluid tiny labeled icon button"
          "ui fluid tiny icon button")
        dark-theme? @(subscribe [:self/dark-theme?])
        touchscreen? @(subscribe [:touchscreen?])]
    [:div.ui.secondary.segment.annotation-view
     {:class (cond-> ""
               new? (str " new-annotation"))}
     [:form.ui.small.form.edit-annotation
      {:on-submit (util/wrap-user-event
                   on-save
                   :timeout false
                   :prevent-default true)}
      [:div.field.selection
       [:label "Selection"]
       (let [display (when (string? selection)
                       (if (<= (count selection) 400)
                         selection
                         (str (subs selection 0 200)
                              nbsp nbsp nbsp "[..........]" nbsp nbsp nbsp
                              (subs selection (- (count selection) 200)))))]
         [:div.ui.small.label.selection-label
          {:class (cond-> "basic"
                    new? (str " new-annotation"))}
          (str "\"" display "\"")])]
      [:div.field.semantic-class
       [:label "Semantic Class"]
       (let [{:keys [new-class]} annotation
             toggle-new-class #(do (set-ann [:new-class] (not new-class))
                                   (set-ann [:semantic-class] nil))
             text-input? (or (not editing?)
                             (empty? class-options)
                             new-class)]
         [:div.fields
          (if text-input?
            [ui/TextInput
             {:value (if editing?
                       (:semantic-class annotation)
                       (or (:semantic-class annotation)
                           (:semantic-class saved)))
              :on-change #(set-ann [:semantic-class] (util/event-input-value %))
              :read-only (not editing?)
              :disabled (not editing?)
              :placeholder "New class name"}]
            [ui/selection-dropdown
             [:div.text (:semantic-class active)]
             (map-indexed
              (fn [i class]
                [:div.item
                 {:key [:semantic-class-option i]
                  :data-value (str class)
                  :class (when (= class (:semantic-class active))
                           "active selected")}
                 [:span (str class)]])
              class-options)
             {:class
              (cond-> (if touchscreen?
                        "ui fluid selection dropdown"
                        "ui fluid search selection dropdown")
                (not editing?) (str " disabled"))
              :onChange
              (fn [v t]
                (set-ann [:semantic-class] v))}])
          [:div.ui.tiny.icon.button.new-semantic-class
           {:class (cond-> ""
                     (or (not editing?)
                         (empty? class-options)) (str " disabled"))
            :on-click (util/wrap-user-event toggle-new-class)}
           (if text-input?
             [:i.list.ul.icon]
             [:i.plus.icon])]])]
      [:div.field.value
       [:label "Value"]
       [ui/TextInput
        {:value (:annotation active)
         :on-change #(set-ann [:annotation] (util/event-input-value %))
         :read-only (not editing?)
         :disabled (not editing?)}]]
      (cond
        editing?
        [:div.field.buttons>div.fields
         [:div.eight.wide.field
          [:button
           {:type "submit"
            :class (cond-> (str button-class " positive")
                     (and (not changed?) (not new?))
                     (str " disabled"))}
           [:i.check.circle.outline.icon]
           (when full-width? "Save")]]
         [:div.eight.wide.field
          [:button
           {:class button-class
            :on-click (util/wrap-user-event
                       #(do (set [:editing-id] nil)
                            (dispatch-sync [::clear-annotations context]))
                       :prevent-default true)}
           [:i.times.icon]
           (when full-width? "Cancel")]]]
        (= current-user original-user-id)
        [:div.field.buttons>div.fields
         [:div.eight.wide.field
          [:button
           {:class button-class
            :on-click
            (util/wrap-user-event
             #(do (dispatch-sync [::clear-annotations context])
                  (set [:editing-id] id))
             :prevent-default true)}
           [:i.blue.pencil.alternate.icon]
           (when full-width? "Edit")]]
         [:div.eight.wide.field
          [:button
           {:class button-class
            :on-click (util/wrap-user-event
                       on-delete :prevent-default true)}
           [:i.red.circle.times.icon]
           (when full-width? "Delete")]]])]]))

#_
(defn AddAnnotation
  "Render absolute-positioned \"Add Annotation\" button
  within AnnotationCapture container."
  [{:keys [class] :as context}]
  (let [pdf? (= class "pdf")

        {:keys [new-annotation selection positions]}
        @(subscribe [::get context])

        {:keys [client-x client-y ctarget-x ctarget-y scroll-x scroll-y]}
        positions

        #_ (println (pr-str {:positions positions}))

        dark-theme? @(subscribe [:self/dark-theme?])

        on-save
        (fn []
          (dispatch-sync [::set context [:selection] ""])
          (util/clear-text-selection)
          (dispatch [:action [:annotator/create-annotation
                              context new-annotation]]))

        [x y]
        ;; position values all recorded from the latest mouse selection event
        [(+
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
     {:class (if (and dark-theme? (not pdf?))
               "dark-theme" "secondary")
      :style {:top (str y "px")
              :left (str x "px")
              :display (if (empty? selection) "none")}
      :on-click (util/wrap-user-event on-save
                                      :timeout false
                                      :stop-propagation true
                                      :prevent-default true)}
     "Add Annotation"]))

(defn AnnotationMenu
  "Create an annotation menu using state."
  [context class]
  (let [annotations @(subscribe (annotator-data-item context))
        enabled? @(subscribe [:annotator/enabled context])
        {:keys [new-annotation]} @(subscribe [::get context])]
    (when enabled?
      (with-loader [(annotator-data-item context)
                    [:annotator/status (:project-id context)]] {}
        [:div.ui.segments.annotation-menu
         {:class class}
         [:div.ui.center.aligned.secondary.segment.menu-header
          [:div.ui.large.fluid.label
           "Select text to annotate"]]
         (when (:id new-annotation)
           [AnnotationEditor context (:id new-annotation)])
         (doall (map
                 (fn [{:keys [id]}]
                   ^{:key (str id)}
                   [AnnotationEditor context id])
                 (->> (vals annotations)
                      (filter #(integer? (:id %)))
                      (sort-by :id)
                      reverse)))
         [:div.ui.secondary.segment]]))))

(defn get-selection
  "Get the current selection, with context, in the dom"
  []
  (let [current-selection ($ js/window getSelection)]
    (when (> ($ current-selection :rangeCount) 0)
      (let [range ($ current-selection getRangeAt 0)]
        {:selection ($ current-selection toString)
         :text-context (-> ($ range :commonAncestorContainer) ($ :data))
         :start-offset ($ range :startOffset)
         :end-offset ($ range :endOffset)}))))

(defn AnnotationCapture
  "Create an Annotator using state. A child is a single element which has text
  to be captured"
  [context child]
  (let [set (fn [path value]
              (dispatch-sync [::set context path value]))
        set-ann (fn [id path value]
                  (dispatch-sync [::set-ann context id path value]))
        set-pos (fn [key value]
                  (dispatch-sync [::set context [:positions key] value]))
        data (subscribe (annotator-data-item context))
        touchscreen? @(subscribe [:touchscreen?])
        update-selection
        (when (and @(subscribe [:self/logged-in?])
                   @(subscribe [:self/member?])
                   @(subscribe [:annotator/enabled context]))
          (fn [e]
            (when false
              (let [ctarget ($ e :currentTarget)
                    {ct-x :left
                     ct-y :top} (util/get-element-position ctarget)
                    {sc-x :left
                     sc-y :top} (util/get-scroll-position)
                    c-x ($ e :clientX)
                    c-y ($ e :clientY)]
                #_
                (do (println (str "ctarget = (" ct-x ", " ct-y ")"))
                    (println (str "scroll = (" sc-x ", " sc-y ")"))
                    (println (str "position = (" c-x ", " c-y ")")))
                (set-pos :scroll-x sc-x)
                (set-pos :scroll-y sc-y)
                (set-pos :ctarget-x ct-x)
                (set-pos :ctarget-y ct-y)
                (set-pos :client-x c-x)
                (set-pos :client-y c-y)))
            (let [selection-map (get-selection)]
              (set [:selection] (:selection selection-map))
              (if (empty? (:selection selection-map))
                (set [:new-annotation] nil)
                (let [entry {:id (str "new-ann-" (util/random-id))
                             :selection (:selection selection-map)
                             :context (dissoc selection-map :selection)
                             :annotation ""
                             :semantic-class nil}]
                  (set [:new-annotation] entry)
                  (set-ann (:id entry) nil entry)
                  (when (not touchscreen?)
                    (js/setTimeout #(-> (js/$ (str ".annotation-view.new-annotation"
                                                   " .field.value input"))
                                        (.focus))
                                   50)))))
            true))]
    [:div.annotation-capture
     {:on-mouse-up update-selection
      :on-touch-end update-selection}
     #_
     (when-not (empty? @(subscribe [::get context [:selection]]))
       [AddAnnotation context])
     child]))

(defn AnnotationToggleButton
  [context & {:keys [on-change class]}]
  (when (util/full-size?)
    (let [enabled? @(subscribe [:annotator/enabled context])]
      [:div.ui.icon.labeled.button.toggle-annotator
       {:on-click
        (util/wrap-user-event
         #(do (dispatch-sync [:annotator/init-view-state context])
              (dispatch-sync [:annotator/enabled context (not enabled?)])
              (when on-change (on-change))))
        :class (cond-> class
                 (and (not (util/annotator-size?))
                      (not enabled?))
                 (str " disabled"))}
       [:i.quote.left.icon]
       (if (and @(subscribe [:self/logged-in?])
                @(subscribe [:self/member?]))
         (if enabled?
           "Disable Annotator"
           "Enable Annotator")
         (if enabled?
           "Hide Annotations"
           "View Annotations"))])))
