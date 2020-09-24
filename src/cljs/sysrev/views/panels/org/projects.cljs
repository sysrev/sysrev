(ns sysrev.views.panels.org.projects
  (:require ["moment" :as moment]
            [clojure.string :as str]
            [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx reg-sub reg-event-db trim-v dispatch]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.panels.create-project :refer [NewProjectButton]]
            [sysrev.views.panels.user.projects :refer [MakePublic]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink]]
            [sysrev.views.panels.org.main :as org]
            [sysrev.views.semantic :refer [Message MessageHeader Icon Loader Table TableHeader
                                           TableHeaderCell TableBody TableRow TableCell]]
            [sysrev.util :as util :refer [index-by]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :projects] {:state-var state
                                           :get-fn panel-get  :set-fn panel-set
                                           :get-sub ::get     :set-event ::set})

(reg-sub :org/projects
         (fn [db [_ org-id]]
           (get-in db [:org org-id :projects])))

(reg-event-db :org/set-projects! [trim-v]
              (fn [db [org-id projects]]
                (assoc-in db [:org org-id :projects] projects)))

(defn get-org-projects! [org-id]
  (let [retrieving? (r/cursor state [:retrieving-projects?])
        error-msg (r/cursor state [:retrieving-projects-error])]
    (reset! retrieving? true)
    (GET (str "/api/org/" org-id "/projects")
         {:header {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [{:keys [result]}]
                     (let [user-projects (index-by :project-id @(subscribe [:self/projects]))
                           member-of? #(get-in user-projects [% :member?])]
                       (reset! retrieving? false)
                       (dispatch [:org/set-projects! org-id
                                  (map #(assoc % :member? (member-of? (:project-id %)))
                                       (:projects result))])))
          :error-handler (fn [{:keys [error]}]
                           (reset! retrieving? false)
                           (reset! error-msg (:message error)))})))

(reg-event-fx :org/get-projects! [trim-v]
              (fn [_ [org-id]]
                (get-org-projects! org-id)
                {}))

(defn- OrgProject [{:keys [name project-id settings member-count admins last-active]}]
  (let [project-owner @(subscribe [:project/owner project-id])
        org-id (-> project-owner vals first)]
    [TableRow {:id (str "project-" project-id) :class "org-project-entry"}
     [TableCell
      [:a.inline-block {:href (project-uri project-id)} name]
      (when (and
             ;; user has proper perms for this project
             (some #{"admin" "owner"} @(subscribe [:self/org-permissions org-id]))
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
  (let [error (r/cursor state [:retrieving-projects-error])
        retrieving? (:retrieving-projects? @state)]
    [:div
     (when (some #{"admin" "owner"} @(subscribe [:org/permissions org-id]))
       [:div {:style {:margin-bottom "1rem"}}
        [NewProjectButton {:project-owner org-id}]])
     (when-not retrieving?
       [OrgProjectList @(subscribe [:org/projects org-id])])
     (when (seq @error)
       [Message {:negative true :onDismiss #(reset! error "")}
        [MessageHeader {:as "h4"} "Get Group Projects error"]
        @error])
     (when retrieving?
       [Loader {:active true :inline "centered"}])]))

(def-panel {:uri "/org/:org-id/projects" :params [org-id]
            :on-route (let [org-id (util/parse-integer org-id)]
                        (org/on-navigate-org org-id panel)
                        (dispatch [:org/get-projects! org-id]))
            :panel panel
            :content (when-let [org-id @(subscribe [::org/org-id])]
                       [OrgProjects org-id])})
