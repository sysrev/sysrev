(ns sysrev.views.panels.project.description
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.loading :as loading]
            [sysrev.markdown :refer [MarkdownComponent]]
            [sysrev.state.ui :as ui-state]
            [sysrev.util :as util]
            [sysrev.views.semantic :refer [Segment]])
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
              ($ js/console log "[Error] set-markdown!")
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
      [:p {:style {:margin-top "0"}}
       "This project does not currently have a description. It's easy to create a description using " [:a {:href "https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet" :target "_blank" :rel "noopener noreferrer"} "Markdown"] " and will help visitors better understand your project."]
      [:div.ui.fluid.button
       {:on-click #(reset! editing? true)}
       "Create Project Description"]]]))

(defn EditMarkdownButton
  "Edit button for the project description. editing? is an atom, mutable? is a boolean"
  [{:keys [editing? mutable?]}]
  (when mutable?
    [:div.ui.tiny.icon.button.edit-markdown
     {:on-click (fn [event]
                  (reset! editing? true))
      :style {:position "absolute"
              :top "0.5em"
              :right "0.5em"
              :margin "0"}}
     [:i.ui.pencil.icon]]))

(defn ProjectDescription
  [context]
  (ensure-state context)
  (let [state (state-cursor context)
        project-id @(subscribe [:active-project-id])
        current-description (subscribe [:project/markdown-description])
        retrieving? (r/cursor state [:retrieving?])
        ignore-create-description-warning?
        (r/cursor state [:ignore-create-description-warning?])
        editing? (r/cursor state [:editing?])
        set-markdown! #(dispatch
                           [:action [:project/markdown-description project-id context %]])
        loading? #(or (loading/any-loading?
                       :only :project/markdown-description)
                      (loading/any-action-running?
                       :only :project/markdown-description))]
    (with-loader [[:project/markdown-description project-id context]] {}
      (cond @editing?
            [Segment {:style {:position "relative"}}
             [:div
              (when-not @editing?
                [EditMarkdownButton {:editing? editing?
                                     :mutable? (or @(subscribe [:member/admin?])
                                                   @(subscribe [:user/admin?]))}])
              [MarkdownComponent
               {:markdown current-description
                :set-markdown! set-markdown!
                :loading? loading?
                :editing? editing?
                :mutable? (or @(subscribe [:member/admin?])
                              @(subscribe [:user/admin?]))}]]]
            (and (not @retrieving?)
                 (str/blank? @current-description)
                 (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
                 (not @ignore-create-description-warning?))
            [ProjectDescriptionNag context]
            (not (str/blank? @current-description))
            [Segment {:class "markdown-component"
                      :style {:position "relative"}}
             [:div
              (when-not @editing?
                [EditMarkdownButton {:editing? editing?
                                     :mutable? (or @(subscribe [:member/admin?])
                                                   @(subscribe [:user/admin?]))}])
              [MarkdownComponent
               {:markdown current-description
                :set-markdown! set-markdown!
                :loading? loading?
                :editing? editing?
                :mutable? (or @(subscribe [:member/admin?])
                              @(subscribe [:user/admin?]))}]]]
            :else
            [:div {:style {:display "none"}}]))))
