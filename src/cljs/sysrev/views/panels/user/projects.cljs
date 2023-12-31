(ns sysrev.views.panels.user.projects
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [reagent.core :as r]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.ui]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.core :refer [ConfirmationDialog]]
            [sysrev.views.panels.create-project :refer [NewProjectButton]]
            [sysrev.views.project :refer [ProjectName]]
            [sysrev.views.semantic :refer
             [Segment Header Grid Row Column Divider Button]]
            [sysrev.util :as util :refer [condensed-number parse-integer sum]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state panel-get panel-set)

(setup-panel-state panel [:user :projects]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :user/projects
  :loaded? (fn [db user-id] (-> (get-in db [:data :user-projects])
                                (contains? user-id)))
  :uri (fn [user-id] (str "/api/user/" user-id "/projects"))
  :process (fn [{:keys [db]} [user-id] {:keys [projects]}]
             {:db (assoc-in db [:data :user-projects user-id] projects)})
  :on-error (fn [{:keys [db error]} [_] _]
              (util/log-err (pr-str error))))

(reg-sub :user/projects (fn [db [_ user-id]] (get-in db [:data :user-projects user-id])))

(defn set-public! [project-id]
  (dispatch [:action [:project/change-settings project-id
                      [{:setting :public-access :value true}]]])
  (dispatch [:project-settings/reset-state!])
  (dispatch [:reload [:project project-id]]))

(defn MakePublic [{:keys [project-id]}]
  (let [confirming? (r/atom false)]
    (r/create-class
     {:reagent-render
      (fn [_]
        [:div
         (when @confirming?
           [ConfirmationDialog
            {:on-cancel #(reset! confirming? false)
             :on-confirm (fn [_e]
                           (set-public! project-id)
                           (reset! confirming? false))
             :title "Confirm Action"
             :message (str "Are you sure you want to make this project publicly viewable? "
                           "Anyone will be able to view the contents of this project.")}])
         (when-not @confirming?
           [Button {:size "mini" :on-click #(reset! confirming? true)
                    :class "set-publicly-viewable"}
            "Set Publicly Viewable"])])
      :component-did-mount (fn [_this]
                             (reset! confirming? false))})))

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
      [Grid {:class "user-activity-content" :columns 3 :style {:display "block"}}
       [Row
        [item-column articles "Articles Reviewed" "articles-reviewed"]
        [item-column labels "Labels Contributed" "labels-contributed"]
        [item-column annotations "Annotations Contributed" "annotations-contributed"]]])))

(defn- UserActivitySummary [projects]
  (let [item-totals (apply merge (->> [:articles :labels :annotations]
                                      (map (fn [k] {k (sum (map k projects))}))))]
    (when (some pos? (vals item-totals))
      [Segment {:class "user-activity-summary"}
       [UserActivityContent {:articles (item-totals :articles)
                             :labels (item-totals :labels)
                             :annotations (item-totals :annotations)}]])))

(defn- UserProject [{:keys [name project-id articles labels annotations settings project-owner]
                     :or {articles 0, labels 0, annotations 0}}]
  [:div {:class "user-project-entry" :data-id project-id :data-name name
         :style {:margin-bottom "1em" :font-size "110%"}}
   [:a {:class "project-link" :href (project-uri project-id)
        :style {:margin-bottom "0.5em" :display "inline-block"}}
    [ProjectName name
     (if (:user-id project-owner)
       @(subscribe [:user/username (:user-id project-owner)])
       (:name project-owner))]]
   (when (and
          ;; user page is for logged in user
          ;; TODO: is this checking that the user is a project admin?
          @(subscribe [:user-panel/self?])
          ;; this project is not public
          (not (:public-access settings))
          ;; the subscription has lapsed
          @(subscribe [:project/subscription-lapsed? project-id]))
     [:div {:style {:margin-bottom "0.5em"}} [MakePublic {:project-id project-id}]])
   [UserActivityContent {:articles articles
                         :labels labels
                         :annotations annotations
                         :count-font-size "1em"}]
   [Divider]])

(defn- UserProjectsList [user-id]
  (with-loader [[:user/projects user-id]] {}
    (let [projects @(subscribe [:user/projects user-id])
          {:keys [public private]}
          (-> (group-by #(if (get-in % [:settings :public-access]) :public :private) projects)
              ;; because we need to exclude anything that doesn't explicitly have a settings keyword
              ;; non-public project summaries are given, but without identifying profile information
              (update :private #(filter :settings %)))
          activity-summary (fn [{:keys [articles labels annotations]}]
                             (+ articles labels annotations))
          sort-activity #(> (activity-summary %1)
                            (activity-summary %2))]
      (when (seq projects)
        [:div.projects
         (when (seq public)
           [Segment
            [Header {:as "h4" :dividing true :style {:font-size "120%"}}
             "Public Projects"]
            [:div {:id "public-projects"}
             (doall (for [project (sort sort-activity public)] ^{:key (:project-id project)}
                      [UserProject project]))]])
         (when (seq private)
           [Segment
            [Header {:as "h4" :dividing true :style {:font-size "120%"}}
             "Private Projects"]
            [:div {:id "private-projects"}
             (doall (for [project (sort sort-activity private)] ^{:key (:project-id project)}
                      [UserProject project]))]])]))))

(defn- UserProjects [user-id]
  (with-loader [[:user/projects user-id]] {}
    (let [projects @(subscribe [:user/projects user-id])]
      [:div
       [:div {:style {:margin-bottom "1em"}}
        [NewProjectButton]]
       [UserActivitySummary (remove #(not (:name %)) projects)] ; disabled projects don't hold a ref to the name
       [UserProjectsList user-id]])))

(def-panel :uri "/user/:user-id/projects" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (dispatch [:data/load [:user/projects user-id]])
              (dispatch [:set-active-panel panel]))
  :content [UserProjects @(subscribe [:user-panel/user-id])])
