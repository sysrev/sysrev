(ns sysrev.views.panels.user.projects
  (:require [ajax.core :refer [GET POST]]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [reagent.core :as r]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.util :as util :refer [condensed-number wrap-prevent-default]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.views.semantic :refer
             [Message MessageHeader Segment Header Grid Row Column Divider Checkbox Button]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:user :projects])

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

(defn MakePublic [{:keys [project-id]}]
  [Button {:size "mini" :on-click (wrap-prevent-default #(make-public! project-id))}
   "Set Publicly Viewable"])

(defn- ActivityColumn [item-count text header-class & [count-font-size]]
  (when (pos? item-count)
    [Column
     [:h2 {:style (cond-> {:margin-bottom "0.10em"}
                    count-font-size (assoc :font-size count-font-size))
           :class header-class} (condensed-number item-count)]
     [:p text]]))

(defn- UserActivityContent [{:keys [articles labels annotations count-font-size]}]
  (when (some pos? [articles labels annotations])
    (let [item-column (fn [item-count text header-class]
                        [ActivityColumn item-count text header-class count-font-size])]
      [Grid {:columns 3 :style {:display "block"}}
       [Row
        [item-column articles "Articles Reviewed" "articles-reviewed"]
        [item-column labels "Labels Contributed" "labels-contributed"]
        [item-column annotations "Annotations Contributed" "annotations-contributed"]]])))

(defn- UserActivitySummary [projects]
  (let [item-totals (apply merge (->> [:articles :labels :annotations]
                                      (map (fn [k] {k (apply + (map k projects))}))))]
    (when (some pos? (vals item-totals))
      [Segment {:id "user-activity-summary"}
       [UserActivityContent {:articles (item-totals :articles)
                             :labels (item-totals :labels)
                             :annotations (item-totals :annotations)}]])))

(defn- UserProject [{:keys [name project-id articles labels annotations settings]
                     :or {articles 0, labels 0, annotations 0}}]
  [:div {:id (str "project-" project-id)
         :class "user-project-entry"
         :style {:margin-bottom "1em" :font-size "110%"}}
   [:a {:href (project-uri project-id)
        :style {:margin-bottom "0.5em" :display "inline-block"}} name]
   (when @(subscribe [:users/is-path-user-id-self?])
     (when-not (:public-access settings)
       [:div {:style {:margin-bottom "0.5em"}} [MakePublic {:project-id project-id}]]))
   [UserActivityContent {:articles articles
                         :labels labels
                         :annotations annotations
                         :count-font-size "1em"}]
   [Divider]])

(defn- UserProjectsList
  [{:keys [user-id]}]
  (let [projects (subscribe [:users/projects user-id])
        error-message (r/cursor state [:retrieving-projects-error-message])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (let [{:keys [public private]}
              (-> (group-by #(if (get-in % [:settings :public-access]) :public :private) @projects)
                  ;; because we need to exclude anything that doesn't explicitly have a settings keyword
                  ;; non-public project summaries are given, but without identifying profile information
                  (update :private #(filter :settings %)))
              activity-summary (fn [{:keys [articles labels annotations]}]
                                 (+ articles labels annotations))
              sort-activity #(> (activity-summary %1)
                                (activity-summary %2))]
          (when (seq @projects)
            [:div.projects
             (when (seq public)
               [Segment
                [Header {:as "h4" :dividing true :style {:font-size "120%"}}
                 "Public Projects"]
                [:div {:id "public-projects"}
                 (doall (for [project (sort sort-activity public)]
                          ^{:key (:project-id project)}
                          [UserProject project]))]])
             (when (seq private)
               [Segment
                [Header {:as "h4" :dividing true :style {:font-size "120%"}}
                 "Private Projects"]
                [:div {:id "private-projects"}
                 (doall (for [project (sort sort-activity private)]
                          ^{:key (:project-id project)}
                          [UserProject project]))]])])))
      :component-will-receive-props
      (fn [this new-argv]
        (get-user-projects! (-> new-argv second :user-id)))
      :component-did-mount (fn [this]
                             (when (empty? @projects)
                               (get-user-projects! user-id)))})))

(defn UserProjects [{:keys [user-id]}]
  (let [projects (subscribe [:users/projects user-id])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [UserActivitySummary @projects]
         (when @(subscribe [:users/is-path-user-id-self?])
           [CreateProject])
         [UserProjectsList {:user-id user-id}]])
      :component-did-mount
      (fn [this]
        (get-user-projects! user-id))})))
