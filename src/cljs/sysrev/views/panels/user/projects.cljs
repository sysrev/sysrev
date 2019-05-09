(ns sysrev.views.panels.user.projects
  (:require [ajax.core :refer [GET POST]]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.util :refer [condensed-number]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.views.semantic :refer [Message MessageHeader Segment Header Grid Row Column Divider Checkbox Button]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:state :panels :user :projects])

(def state (r/cursor app-db [:state :panels panel]))

(reg-sub :users/projects
         (fn [db [event user-id]]
           (get-in db [user-id :projects])))

(reg-event-db
 :users/set-projects!
 [trim-v]
 (fn [db [user-id projects]]
   (assoc-in db [user-id :projects] projects)))

(defn get-user-projects! [user-id]
  (let [retrieving-projects? (r/cursor state [:retrieving-projects?])
        projects (r/cursor state [:projects])
        error-message (r/cursor state [:retrieving-projects-error-message])]
    (reset! retrieving-projects? true)
    (GET (str "/api/user/" user-id "/projects")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-projects? false)
                     (dispatch [:users/set-projects! user-id (-> response :result :projects)]))
          :error-handler (fn [error-response]
                           (.log js/console (clj->js error-response))
                           (reset! retrieving-projects? false)
                           (reset! error-message (get-in error-response [:response :error :message])))})))

(defn make-public! [project-id]
  (POST "/api/change-project-settings"
        {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
         :params {:project-id project-id :changes [{:setting :public-access
                                                    :value true}]}
         :handler (fn [response]
                    ;; only logged in users can make a project public
                    (get-user-projects! @(subscribe [:self/user-id])))}))

(defn ActivitySummary
  [{:keys [articles labels annotations count-font-size]}]
  (let [header-margin-bottom "0.10em"]
    (when (or (> articles 0)
              (> labels 0)
              (> annotations 0))
      [Grid {:columns 3
             :style {:display "block"}}
       [Row
        (when (> articles 0)
          [Column
           [:h2 {:style (cond-> {:margin-bottom header-margin-bottom}
                          count-font-size (assoc :font-size count-font-size))
                 :class "articles-reviewed"} (condensed-number articles)]
           [:p "Articles Reviewed"]])
        (when (> labels 0)
          [Column
           [:h2 {:style (cond-> {:margin-bottom header-margin-bottom}
                          count-font-size (assoc :font-size count-font-size))
                 :class "labels-contributed"} (condensed-number labels)]
           [:p "Labels Contributed"]])
        (when (> annotations 0)
          [Column
           [:h2 {:style (cond-> {:margin-bottom header-margin-bottom}
                          count-font-size (assoc :font-size count-font-size))
                 :class "annotations-contributed"} (condensed-number annotations)]
           [:p "Annotations Contributed"]])]])))

(defn UserActivitySummary
  [projects]
  (let [count-items (fn [projects kw]
                      (->> projects (map kw) (apply +)))
        articles (count-items projects :articles)
        labels (count-items projects :labels)
        annotations (count-items projects :annotations)]
    (when (> (+ articles labels annotations) 0)
      [Segment {:id "user-activity-summary"}
       [ActivitySummary {:articles articles
                         :labels labels
                         :annotations annotations}]])))
(defn MakePublic
  [{:keys [project-id]}]
  [Button {:on-click (fn [e]
                       ($ e preventDefault)
                       (make-public! project-id))
           :size "mini"}
   "Set Publicly Viewable"])

(defn Project
  [{:keys [name project-id articles labels annotations settings]
    :or {articles 0
         labels 0
         annotations 0}}]
  [:div {:style {:margin-bottom "1em"}
         :id (str "project-" project-id)}
   [:div
    [:a {:href (str "/p/" project-id)
                              :style {:margin-bottom "0.5em"
                                      :display "inline-block"
                                      :font-size "2em"}}  name]
    (when @(subscribe [:users/is-path-user-id-self?])
      (when-not (:public-access settings)
        [:div {:style {:margin-bottom "0.5em"}} [MakePublic {:project-id project-id}]]))]
   [:div
    [ActivitySummary {:articles articles
                      :labels labels
                      :annotations annotations
                      :count-font-size "1em"}]]
   [Divider]])

(defn UserProjects
  [{:keys [user-id]}]
  (let [projects (subscribe [:users/projects user-id])
        error-message (r/cursor state [:retrieving-projects-error-message])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [public private]} (group-by #(if (get-in % [:settings :public-access]) :public :private) @projects)
              activity-summary (fn [{:keys [articles labels annotations]}]
                                 (+ articles labels annotations))
              sort-fn #(> (activity-summary %1)
                          (activity-summary %2))
              ;; because we need to exclude anything that doesn't explicitly have a settings keyword
              ;; non-public project summaries are given, but without identifying profile information
              private (filter #(contains? % :settings) private)]
          (when-not (empty? @projects)
            [:div.projects
             (when-not (empty? public)
               [Segment
                [Header {:as "h4"
                         :dividing true}
                 "Public Projects"]
                [:div {:id "public-projects"}
                 (->> public
                      (sort sort-fn)
                      (map (fn [project]
                             ^{:key (:project-id project)}
                             [Project project])))]])
             (when-not (empty? private)
               [Segment
                [Header {:as "h4"
                         :dividing true}
                 "Private Projects"]
                [:div {:id "private-projects"}
                 (->> private
                      (sort sort-fn)
                      (map (fn [project]
                             ^{:key (:project-id project)}
                             [Project project])))]])])))
      :component-will-receive-props
      (fn [this new-argv]
        (get-user-projects! (-> new-argv second :user-id)))
      :component-did-mount (fn [this]
                             (when (empty? @projects)
                               (get-user-projects! user-id)))})))

(defn Projects
  [{:keys [user-id]}]
  (let [projects (subscribe [:users/projects user-id])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [UserActivitySummary @projects]
         (when @(subscribe [:users/is-path-user-id-self?])
           [CreateProject])
         [UserProjects {:user-id user-id}]])
      :component-did-mount
      (fn [this]
        (get-user-projects! user-id))})))
