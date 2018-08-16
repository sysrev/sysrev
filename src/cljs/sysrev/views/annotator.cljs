(ns sysrev.views.annotator
  (:require [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
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
            [sysrev.util :as util]
            [sysrev.views.components :refer [TextInput]])
  (:require-macros [reagent.interop :refer [$]]))

(def view :annotator)

(def initial-annotator-state
  {:annotation-retrieving? false
   :enabled? false})

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
   (ui-state/set-view-field
    db view [context :annotations] {})))

(reg-event-db
 ::remove-ann
 [trim-v]
 (fn [db [context id]]
   (let [path [context :annotations]
         annotations (ui-state/get-view-field db path view)]
     (ui-state/set-view-field db view path
                              (dissoc annotations id)))))

(reg-sub
 :annotator/enabled?
 (fn [[_ context]]
   [(subscribe [::get context [:enabled?]])])
 (fn [[enabled?]] enabled?))

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
              (list [:reload (annotator-data-item context)]
                    [::clear-annotations context]
                    [::set context [:editing-id] annotation-id])}))

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

(def-data :annotator/article
  :loaded? (fn [db project-id article-id]
             (contains? (get-in db [:data :project project-id
                                    :annotator :article])
                        article-id))
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
                         (util/vector->hash-map annotations :id)))}
      {})))

(reg-sub
 :annotator/article
 (fn [db [_ project-id article-id]]
   (get-in db [:data :project project-id
               :annotator :article article-id])))

(def-data :annotator/article-pdf
  :loaded? (fn [db project-id article-id pdf-key]
             (contains? (get-in db [:data :project project-id
                                    :annotator :article-pdf article-id])
                        pdf-key))
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
                         (util/vector->hash-map annotations :id)))}
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
        data @(subscribe (annotator-data-item context))
        saved (get data id)
        annotation (get @(subscribe [::annotations context]) id)
        active {:annotation (or (:annotation annotation)
                                (:annotation saved))
                :semantic-class (or (:semantic-class annotation)
                                    (:semantic-class saved))}
        changed? (not= active (select-keys saved [:annotation :semantic-class]))
        {:keys [editing-id]} @(subscribe [::get context])
        editing? (= id editing-id)
        original-user-id (:user-id saved)
        current-user @(subscribe [:self/user-id])
        dark-theme? @(subscribe [:self/dark-theme?])
        on-save #(dispatch [:action [:annotator/update-annotation
                                     context id
                                     (:annotation active)
                                     (:semantic-class active)]])
        on-delete #(do (dispatch-sync [::remove-ann context id])
                       (dispatch [:action [:annotator/delete-annotation
                                           context id]]))]
    [:div.ui.secondary.segment.annotation-view
     [:form.ui.small.form.edit-annotation
      {:on-submit (util/wrap-user-event
                   on-save
                   :timeout false
                   :prevent-default true)}
      [:div.field
       [:label "Selection"]
       [:div.ui.small.label.selection-label
        {:class (if dark-theme?
                  ""
                  "basic")}
        (str "\"" (:selection saved) "\"")]]
      [TextInput
       {:value (:semantic-class active)
        :on-change #(set-ann [:semantic-class] (util/event-input-value %))
        :read-only (not editing?)
        :disabled (not editing?)
        :label "Semantic Class"}]
      [TextInput
       {:value (:annotation active)
        :on-change #(set-ann [:annotation] (util/event-input-value %))
        :read-only (not editing?)
        :disabled (not editing?)
        :label "Value"}]
      (cond
        (= id editing-id)
        [:div.field.buttons>div.fields
         [:div.eight.wide.field
          [:button.ui.positive.fluid.tiny.labeled.icon.button
           {:type "submit"
            :class (if changed? nil "disabled")}
           [:i.check.circle.outline.icon]
           "Save"]]
         [:div.eight.wide.field
          [:button.ui.fluid.tiny.labeled.icon.button
           {:on-click (util/wrap-user-event
                       #(do (set [:editing-id] nil)
                            (when (and (empty? (:annotation active))
                                       (empty? (:semantic-class active)))
                              (on-delete))
                            (dispatch-sync [::clear-annotations context]))
                       :prevent-default true)}
           [:i.times.icon]
           "Cancel"]]]
        (= current-user original-user-id)
        [:div.field.buttons>div.fields
         [:div.eight.wide.field
          [:button.ui.fluid.tiny.labeled.icon.button
           {:on-click
            (util/wrap-user-event
             #(do (dispatch-sync [::clear-annotations context])
                  (set [:editing-id] id))
             :prevent-default true)}
           [:i.blue.pencil.alternate.icon]
           "Edit"]]
         [:div.eight.wide.field
          [:button.ui.fluid.tiny.labeled.icon.button
           {:on-click (util/wrap-user-event
                       on-delete :prevent-default true)}
           [:i.red.circle.times.icon]
           "Delete"]]])]]))

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
        enabled? @(subscribe [:annotator/enabled? context])]
    (when enabled?
      [:div.ui.segments.annotation-menu
       {:class class}
       [:div.ui.secondary.header.segment
        [:h3.ui.center.aligned.header
         "Annotations"]]
       (if (empty? (vals annotations))
         [:div.ui.secondary.segment]
         (doall (map
                 (fn [{:keys [id]}]
                   ^{:key (str id)}
                   [AnnotationEditor context id])
                 (->> (vals annotations)
                      (sort-by :id)
                      reverse))))])))

(defn get-selection
  "Get the current selection, with context, in the dom"
  []
  (let [current-selection ($ js/window getSelection)
        range ($ current-selection getRangeAt 0)]
    {:selection ($ current-selection toString)
     :text-context (-> ($ range :commonAncestorContainer) ($ :data))
     :start-offset ($ range :startOffset)
     :end-offset ($ range :endOffset)}))

(defn AnnotationCapture
  "Create an Annotator using state. A child is a single element which has text
  to be captured"
  [context child]
  (let [set (fn [path value]
              (dispatch-sync [::set context path value]))
        set-pos (fn [key value]
                  (dispatch-sync [::set context [:positions key] value]))
        data @(subscribe (annotator-data-item context))
        last-semantic-class (->> (vals data)
                                 (filter :semantic-class)
                                 (sort-by :id)
                                 reverse
                                 first
                                 :semantic-class)]
    [:div.annotation-capture
     {:on-mouse-up
      (fn [e]
        (when (and @(subscribe [:self/logged-in?])
                   @(subscribe [:self/member?])
                   @(subscribe [::get context [:enabled?]]))
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
            (set-pos :client-y c-y))
          ;; TODO: util/wrap-user-event or js/setTimeout on the (get-selection)
          ;;       part if there are still issues with text selection
          (let [selection-map (get-selection)]
            (set [:selection] (:selection selection-map))
            (when (not-empty (:selection selection-map))
              (set [:new-annotation]
                   {:selection (:selection selection-map)
                    :context (dissoc selection-map :selection)
                    :annotation ""
                    :semantic-class (or last-semantic-class "")})))))}
     (when-not (empty? @(subscribe [::get context [:selection]]))
       [AddAnnotation context])
     child]))

(defn AnnotationToggleButton
  [context & {:keys [on-change class]}]
  (when (util/desktop-size?)
    (let [{:keys [enabled?]} @(subscribe [::get context])]
      [:div.ui.label.button
       {:on-click
        (util/wrap-user-event
         #(do (dispatch-sync [:annotator/init-view-state context])
              (dispatch-sync [::set context [:enabled?] (not enabled?)])
              (when on-change (on-change))))
        :class class}
       (if (and @(subscribe [:self/logged-in?])
                @(subscribe [:self/member?]))
         (if enabled?
           "Disable Annotator"
           "Enable Annotator")
         (if enabled?
           "Hide Annotations"
           "View Annotations"))])))
