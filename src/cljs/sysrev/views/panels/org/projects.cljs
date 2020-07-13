(ns sysrev.views.panels.org.projects
  (:require ["moment" :as moment]
            [clojure.string :as str]
            [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx reg-sub reg-event-db trim-v dispatch]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.panels.project.new :refer [NewProjectButton]]
            [sysrev.views.panels.user.projects :refer [MakePublic]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink]]
            [sysrev.views.semantic :refer
             [Message MessageHeader Icon Loader Table TableHeader TableHeaderCell
              TableBody TableRow TableCell]]
            [sysrev.util :refer [index-by]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :projects] {:state-var state})

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
                       (dispatch [:org/set-projects! org-id (map #(assoc % :member? (member-of? (:project-id %)))
                                                                 (:projects result))])))
          :error-handler (fn [{:keys [error]}]
                           (reset! retrieving? false)
                           (reset! error-msg (:message error)))})))

(reg-event-fx :org/get-projects! [trim-v]
              (fn [_ [org-id]]
                (get-org-projects! org-id)
                {}))

(defn OrgProject [{:keys [name project-id settings member-count admins last-active]}]
  (let [project-owner @(subscribe [:project/owner project-id])
        org-id (-> project-owner vals first)]
    [TableRow {:id (str "project-" project-id)
               :class "org-project-entry"}
     [TableCell
      [:a {:href (project-uri project-id)
           :style {:display "inline-block"}} name]
      (when (and
             ;; user has proper perms for this project
             (some #{"admin" "owner"} @(subscribe [:self/org-permissions org-id]))
             ;; this project is not public
             (not (:public-access settings))
             ;; the subscription has lapsed
             @(subscribe [:project/subscription-lapsed? project-id]))
        [:div {:style {:margin-bottom "0.5em"}} [MakePublic {:project-id project-id}]])]
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

(defn OrgProjectList [_projects]
  (let [;; {:keys [public private]}
        ;; (-> (group-by #(if (get-in % [:settings :public-access]) :public :private) projects)
        ;;     ;; because we need to exclude anything that doesn't explicitly have a settings keyword
        ;;     ;; non-public project summaries are given, but without identifying profile information
        ;;     (update :private #(filter :settings %)))
        column (r/atom :name)
        direction (r/atom true)
        sort-fn (fn [name]
                  (reset! column name)
                  (swap! direction not))]
    (fn [projects]
      (if (seq projects)
        [:div.projects
         [:div {:id "projects"}
          [Table {:sortable true}
           [TableHeader
            [TableRow
             [TableHeaderCell {:onClick #(sort-fn :name)
                               :sorted (when (= @column :name)
                                         (if @direction
                                           "ascending"
                                           "descending"))} "Project Name"]
             [TableHeaderCell {:onClick #(sort-fn :last-active)
                               :sorted (when (= @column :last-active)
                                         (if @direction
                                           "ascending"
                                           "descending"))
                               :text-align "center"} "Last Active"]
             [TableHeaderCell {:text-align "center"
                               #_ :on-click #_ (sort-fn :admins)
                               #_ :sorted #_ (when (= @column :admins)
                                               (if @direction "ascending" "descending"))
                               } "Administrators"]
             [TableHeaderCell {:text-align "center"
                               :onClick #(sort-fn :member-count)
                               :sorted (when (= @column :member-count)
                                         (if @direction
                                           "ascending"
                                           "descending")) } "Team Size"]
             [TableHeaderCell {:text-align "center"} "Settings"]]]
           [TableBody
            (doall (for [project (cond->> projects
                                   true (sort-by @column)
                                   (and (not= @column :last-active)
                                        (not @direction)) (reverse)
                                   (and (= @column :last-active)
                                        @direction) (reverse))]
                     ^{:key (:project-id project)}
                     [OrgProject project]))]]
          #_[Grid
             (doall (for [project projects]
                      ^{:key (:project-id project)}
                      [OrgProject project]))]]
         #_(when (seq public)
             [Segment
              [Header {:as "h4" :dividing true :style {:font-size "120%"}}
               "Public Projects"]
              [:div {:id "public-projects"}
               (doall (for [project public]
                        ^{:key (:project-id project)}
                        [OrgProject project]))]])
         #_(when (seq private)
             [Segment
              [Header {:as "h4" :dividing true :style {:font-size "120%"}}
               "Private Projects"]
              [:div {:id "private-projects"}
               (doall (for [project #_(sort sort-activity private) private]
                        ^{:key (:project-id project)}
                        [OrgProject project]))]])]
        [Message [:h4 "This organization doesn't have any public projects"]]))))

(defn OrgProjects [{:keys [org-id]}]
  (let [error (r/cursor state [:retrieving-projects-error])]
    (r/create-class
     {:reagent-render
      (fn [_]
        (let [retrieving? (:retrieving-projects? @state)]
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
      :component-did-mount #(dispatch [:org/get-projects! org-id])})))
