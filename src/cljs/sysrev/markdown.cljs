(ns sysrev.markdown
  (:require [clojure.string :as str]
            [cljsjs.semantic-ui-react]
            [cljsjs.showdown]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.ui :as ui-state]
            [sysrev.util :as util])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [with-loader]]))

(def view :markdown)

(defn state-cursor [context]
  (let [{:keys [panel]} context]
    (r/cursor app-db [:state :panels panel :views view])))
(defn set-state [db context path value]
  (ui-state/set-view-field db view path value (:panel context)))

(def initial-state {:editing? false
                    :draft-description ""
                    :ignore-create-description-warning? false})

(defn ensure-state [context]
  (let [state (state-cursor context)]
    (when (nil? @state)
      (reset! state initial-state))))

(def semantic-ui js/semanticUIReact)
(def TextArea (r/adapt-react-class (goog.object/get semantic-ui "TextArea")))

;; security, particularly regarding XSS attacks is a big concern when letting
;; users generate their own HTML
;; see: https://github.com/showdownjs/showdown/wiki/Markdown's-XSS-Vulnerability-(and-how-to-mitigate-it)
;; unfortunately, there is little in the way in regards to mitigation at that article
;;
;; Some example of md that could cause issues:
;; [some text](javascript:alert('xss'))
;;
;; > hello <a name="n"
;; > href="javascript:alert('xss')">*you*</a>
;;
;; This currently uses the https://github.com/cure53/DOMPurify
;;
;; This issue https://github.com/markedjs/marked/issues/1232 shows additional filters
;; dompurify: https://www.npmjs.com/package/dompurify
;; sanitize-html: https://www.npmjs.com/package/sanitize-html
;; insane: https://www.npmjs.com/package/insane
;; marked-sanitizer-github: https://www.npmjs.com/package/marked-sanitizer-github

(defn create-markdown-html [markdown]
  (let [converter (js/showdown.Converter.
                   (clj->js {:simpleLineBreaks true}))]
    ;; this is a hook to add target=_blank to anything with a href
    ($ js/DOMPurify addHook
       "afterSanitizeAttributes"
       (fn [node]
         (when ($ node hasAttribute "href")
           ($ node setAttribute "target" "_blank"))))
    (->> markdown
         ($ converter makeHtml)
         ($ js/DOMPurify sanitize))))

(defn RenderMarkdown
  [markdown]
  [:div {:style {:word-wrap "break-word"}
         :dangerouslySetInnerHTML
         {:__html (create-markdown-html markdown)}}])

(defn EditMarkdownButton
  [context]
  (let [state (state-cursor context)
        draft-description (r/cursor state [:draft-description])
        current-description (subscribe [:project/markdown-description])
        editing? (r/cursor state [:editing?])]
    (when (or @(subscribe [:member/admin?])
              @(subscribe [:user/admin?]))
      [:div.ui.tiny.icon.button.edit-markdown
       {:on-click (fn [event]
                    (reset! draft-description (or @current-description ""))
                    (reset! editing? true))
        :style {:position "absolute"
                :top "0.5em"
                :right "0.5em"
                :margin "0"}}
       [:i.ui.pencil.icon]])))

(defn MarkdownComponent
  [context & {:keys [id]
              :or {id "project-description"}}]
  (let [state (state-cursor context)
        project-id @(subscribe [:active-project-id])
        editing? (r/cursor state [:editing?])
        current-description (subscribe [:project/markdown-description])
        draft-description (r/cursor state [:draft-description])
        set-description!
        #(dispatch [:action [:project/markdown-description project-id context %]])
        changed? (not= (or @current-description "")
                       (or @draft-description ""))
        loading? (or (loading/any-loading?
                      :only :project/markdown-description)
                     (loading/any-action-running?
                      :only :project/markdown-description))]
    [:div.ui.segment.markdown-component
     {:style {:position "relative"}}
     [:div.ui.panel {:id id}
      (when-not @editing?
        [EditMarkdownButton context])
      (if @editing?
        [:div.editor-view
         [:div.ui.segments
          [:div.ui.form.secondary.segment
           [TextArea {:fluid "true"
                      :autoHeight true
                      :disabled loading?
                      :placeholder "Enter a Markdown description"
                      :on-change #(reset! draft-description
                                          (-> ($ % :target) ($ :value)))
                      :default-value (or @draft-description "")}]]
          [:div.ui.secondary.middle.aligned.grid.segment
           [:div.eight.wide.left.aligned.column
            [:a.markdown-link
             {:href "https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet"
              :target "_blank"
              :rel "noopener noreferrer"}
             "Markdown Cheatsheet"]]
           [:div.eight.wide.right.aligned.column.form-buttons
            [:button.ui.tiny.positive.icon.labeled.button
             {:class (cond-> ""
                       (not changed?) (str " disabled")
                       loading?       (str " loading"))
              :on-click #(set-description! @draft-description)}
             [:i.circle.check.icon]
             "Save"]
            [:button.ui.tiny.icon.labeled.button
             {:on-click #(reset! editing? false)
              :class (when loading? "disabled")
              :style {:margin-right "0"}}
             [:i.times.icon]
             "Cancel"]]]]
         [:div.ui.segments
          [:div.ui.secondary.header.segment
           [:h5.ui.header "Preview"]]
          [:div.ui.secondary.segment
           [RenderMarkdown @draft-description]]]]
        [:div [RenderMarkdown @current-description]])]]))

(def-data :project/markdown-description
  :loaded? (fn [db project-id _]
             (-> (get-in db [:data :project project-id])
                 (contains? :markdown-description)))
  :uri (fn [_ _] "/api/project-description")
  :content (fn [project-id _] {:project-id project-id})
  :prereqs (fn [project-id _] [[:identity] [:project project-id]])
  :process (fn [{:keys [db]} [project-id context] result]
             {:db (-> (assoc-in db [:data :project project-id :markdown-description]
                                (-> result :project-description))
                      (set-state context [:editing?] false))})
  :on-error (fn [{:keys [db error]} [project-id context] _]
              ($ js/console log "[Error] get-description!")
              {:db (set-state db context [:editing?] false)}))

(def-action :project/markdown-description
  :uri (fn [project-id context value] "/api/project-description")
  :content (fn [project-id context value]
             {:project-id project-id :markdown value})
  :process (fn [{:keys [db]} [project-id context value] result]
             {:dispatch [:reload [:project/markdown-description
                                  project-id context]]})
  :on-error (fn [{:keys [db error]} [project-id context value] _]
              ($ js/console log "[Error] set-description!")
              {:db (set-state db context [:editing?] false)}))

(reg-sub
 :project/markdown-description
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:markdown-description project)))

(defn ProjectDescriptionNag
  [context]
  (let [state (state-cursor context)
        project-id @(subscribe [:active-project-id])
        ignore-create-description-warning?
        (r/cursor state [:ignore-create-description-warning?])
        editing? (r/cursor state [:editing?])]
    [:div.ui.icon.message.read-only-message.project-description
     [:i.close.icon
      {:on-click #(reset! ignore-create-description-warning? true)}]
     [:div.content
      [:div.header "Create a project description, your users will thank you!"]
      [:p "This project does not currently have a description. It's easy to create a description using " [:a {:href "https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet" :target "_blank" :rel "noopener noreferrer"} "Markdown"] " and will help visitors better understand your project."]
      [:div.ui.two.column.middle.aligned.grid
       [:div.ui.left.aligned.column
        [:div.ui.button
         {:on-click #(reset! editing? true)}
         "Create Project Description"]]]]]))

(defn ProjectDescription
  [context]
  (ensure-state context)
  (let [state (state-cursor context)
        project-id @(subscribe [:active-project-id])
        current-description (subscribe [:project/markdown-description])
        retrieving? (r/cursor state [:retrieving?])
        ignore-create-description-warning?
        (r/cursor state [:ignore-create-description-warning?])
        editing? (r/cursor state [:editing?])]
    (with-loader [[:project/markdown-description project-id context]] {}
      (cond @editing?
            [MarkdownComponent context]
            (and (not @retrieving?)
                 (str/blank? @current-description)
                 (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
                 (not @ignore-create-description-warning?))
            [ProjectDescriptionNag context]
            (not (str/blank? @current-description))
            [MarkdownComponent context]
            :else
            [:div {:style {:display "none"}}]))))
