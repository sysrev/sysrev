(ns sysrev.views.panels.org.projects
  (:require ["moment" :as moment]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.core :refer [CursorMessage]]
            [sysrev.views.panels.create-project :refer [NewProjectButton]]
            [sysrev.views.panels.user.projects :refer [MakePublic]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink]]
            [sysrev.views.panels.org.main :as org]
            [sysrev.views.semantic :refer [Message Icon Loader Table TableHeader
                                           TableHeaderCell TableBody TableRow TableCell]]
            [sysrev.util :as util :refer [index-by]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :projects]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :org/projects
  :loaded?  (fn [db org-id]
              (-> (get-in db [:org org-id])
                  (contains? :projects)))
  :uri      (fn [org-id] (str "/api/org/" org-id "/projects"))
  :process  (fn [{:keys [db]} [org-id] {:keys [projects]}]
              (let [user-projects (index-by :project-id @(subscribe [:self/projects]))
                    member-of? #(get-in user-projects [% :member?])]
                {:db (-> (assoc-in db [:org org-id :projects]
                                   (for [p projects]
                                     (assoc p :member? (member-of? (:project-id p)))))
                         (panel-set :get-projects-error nil))}))
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db :get-projects-error (:message error))}))

(reg-sub  :org/projects
          (fn [db [_ org-id]]
            (get-in db [:org org-id :projects])))

(defn- OrgProject [{:keys [name project-id settings member-count admins last-active]}]
  (let [{:keys [group-id]} @(subscribe [:project/owner project-id])]
    [TableRow {:id (str "project-" project-id) :class "org-project-entry"}
     [TableCell
      [:a.inline-block {:href (project-uri project-id)} name]
      (when (and
             ;; user has proper perms for this project
             @(subscribe [:org/owner-or-admin? group-id true])
             ;; this project is not public
             (not (:public-access settings))
             ;; the subscription has lapsed
             @(subscribe [:project/subscription-lapsed? project-id]))
        [:div {:style {:margin-bottom "0.5em"}}
         [MakePublic {:project-id project-id}]])]
     [TableCell {:text-align "center"}
      (or (some-> last-active (moment.) (.fromNow)) "never")]
     [TableCell {:text-align "center"}
      (doall (for [user admins] ^{:key (str (:user-id user) "-" project-id)}
               [:div [UserPublicProfileLink
                      {:user-id (:user-id user)
                       :display-name (first (str/split (:email user) #"@"))}]]))]
     [TableCell {:text-align "center"} member-count]
     [TableCell {:text-align "center"}
      [:a {:href (str (project-uri project-id) "/settings")}
       [Icon {:name "setting"}]]]]))

(defn- OrgProjectList [_projects]
  (let [column (r/atom :name)
        direction (r/atom true)
        sort-fn (fn [name]
                  (reset! column name)
                  (swap! direction not))]
    (fn [projects]
      (if (seq projects)
        (let [sorted #(when (= @column %)
                        (if @direction "ascending" "descending"))]
          [:div.projects
           [:div {:id "projects"}
            [Table {:sortable true}
             [TableHeader
              [TableRow
               [TableHeaderCell {:onClick #(sort-fn :name)
                                 :sorted (sorted :name)} "Project Name"]
               [TableHeaderCell {:text-align "center"
                                 :onClick #(sort-fn :last-active)
                                 :sorted (sorted :last-active)} "Last Active"]
               [TableHeaderCell {:text-align "center"
                                 #_ :on-click #_ (sort-fn :admins)
                                 #_ :sorted #_ (sorted :admins)} "Administrators"]
               [TableHeaderCell {:text-align "center"
                                 :onClick #(sort-fn :member-count)
                                 :sorted (sorted :member-count)} "Team Size"]
               [TableHeaderCell {:text-align "center"} "Settings"]]]
             [TableBody
              (doall (for [project (cond-> (sort-by @column projects)
                                     (case (boolean @direction)
                                       true  (= @column :last-active)
                                       false (not= @column :last-active))
                                     (reverse))]
                       ^{:key (:project-id project)}
                       [OrgProject project]))]]]])
        [Message [:h4 "This organization doesn't have any public projects"]]))))

(defn OrgProjects [org-id]
  (let [error (r/cursor state [:get-projects-error])
        loading? (data/loading? :org/projects)]
    [:div
     (when @(subscribe [:org/owner-or-admin? org-id false])
       [:div {:style {:margin-bottom "1rem"}}
        [NewProjectButton {:project-owner org-id}]])
     (when-not loading?
       [OrgProjectList @(subscribe [:org/projects org-id])])
     [CursorMessage error {:negative true}]
     (when loading?
       [Loader {:active true :inline "centered"}])]))

(def-panel :uri "/org/:org-id/projects" :params [org-id] :panel panel
  :on-route (let [org-id (util/parse-integer org-id)]
              (org/on-navigate-org org-id panel)
              (dispatch [:data/load [:org/projects org-id]]))
  :content (when-let [org-id @(subscribe [::org/org-id])]
             [OrgProjects org-id]))
