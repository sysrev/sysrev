(ns sysrev.views.panels.create-project
  (:require [medley.core :as medley :refer [find-first]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.data.core :as data]
            [sysrev.nav :refer [make-url]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.views.semantic :refer [Divider Dropdown Form Grid Input Row Column
                                           Button Icon Radio Header]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:new-project]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-action :create-project
  :uri (fn [_ _] "/api/create-project")
  :content (fn [project-name public-access?] {:project-name project-name
                                              :public-access public-access?})
  :process (fn [_ _ {:keys [success message project]}]
             (when success
               {:dispatch-n (list [:reload [:identity]]
                                  [:project/navigate (:project-id project)])})))

(def-action :create-org-project
  :uri (fn [_ org-id _] (str "/api/org/" org-id "/project"))
  :content (fn [project-name _ public-access?] {:project-name project-name
                                                :public-access public-access?})
  :process (fn [_ _ {:keys [success message project]}]
             (when success
               {:dispatch-n (list [:reload [:identity]]
                                  [:project/navigate (:project-id project)])})))

(defn NewProjectButton [& [{:keys [project-owner]}]]
  [Button {:id "new-project"
           :size "small" :positive true
           :href (if (int? project-owner)
                   (make-url "/new" {:project_owner project-owner})
                   "/new")
           :on-click (util/scroll-top)}
   [Icon {:name "list alternate outline"}] "New"])

(defn- OwnerDropdown []
  (let [user-id @(subscribe [:self/user-id])
        project-owner (r/cursor state [:project-owner])
        options (vec (cons {:text @(subscribe [:user/username]) :value "current-user"}
                           (for [{:keys [group-name group-id]}
                                 (filter #(some #{"owner" "admin"} (:permissions %))
                                         @(subscribe [:user/orgs user-id]))]
                             {:text group-name :value group-id})))]
    [Dropdown {:fluid true
               :selection true
               :options options
               :value (or @project-owner "current-user")
               :on-change (fn [_event data]
                            (reset! project-owner (.-value data)))}]))

(defn- CreateProject []
  (let [user-id @(subscribe [:self/user-id])
        project-name (r/cursor state [:project-name])
        project-owner (r/cursor state [:project-owner])
        public-access? (r/cursor state [:public-access])
        orgs @(subscribe [:user/orgs user-id])
        plan (if (= @project-owner "current-user")
               @(subscribe [:user/current-plan])
               (:plan (->> orgs (find-first #(= @project-owner (:group-id %))))))
        owner-has-pro? (plans-info/pro? (:nickname plan))]
    [Form {:id "create-project"
           :on-submit #(when @project-name
                         (if (= @project-owner "current-user")
                           (run-action :create-project
                                       @project-name @public-access?)
                           (run-action :create-org-project
                                       @project-name @project-owner @public-access?)))}
     [:div
      [:div {:id "create-project-header"}
       [Header {:as "h2" :style {:margin-bottom "0.5em"}}
        "Create a new project"]
       [:p "A project contains articles that are labeled by reviewers."]]
      [Divider]
      [Grid {:class "owner-name-form" :doubling true}
       [Row {:style {:padding-bottom "0"}}
        [Column {:width (if (util/mobile?) 6 3)}   [:b "Owner " [:sup {:style {:color "red"
                                                                               :font-size "1em"}} "*"]]]
        [Column {:width (if (util/mobile?) 10 5)}  [:b "Project Name " [:sup {:style {:color "red"
                                                                                      :font-size "1em"}} "*"]]]]
       [Row [Column {:width (if (util/mobile?) 6 3)}
             [OwnerDropdown]]
        [Column {:text-align "left" :width (if (util/mobile?) 10 5)}
         [Input {:placeholder "Project Name"
                 :class "project-name"
                 :fluid true
                 :autoFocus true
                 :on-change (util/on-event-value #(reset! project-name %))}]]]]
      [Divider]
      [Grid {:class "public-or-private" :doubling true}
       [Row [Column {:width 12}
             [Radio {:style {:float "left" :margin-top "0.90rem"}
                     :checked @public-access?
                     :on-click #(reset! public-access? true)}]
             [:div {:style {:float "left" :margin-left "0.5rem"}}
              [Icon {:name "list alternate outline" :size "huge"
                     :style {:float "left" :margin-top "0.5rem"}}]
              [:div {:style {:float "left" :margin-top "0.25rem" :margin-left "0.5rem"}}
               [:p {:style {:margin-bottom "0.25rem" :font-size "1.9rem"}}
                "Public"]
               [:p {:style {:margin-top "0.25rem"}}
                "Anyone on the internet can see this project."]]]]]
       [Row
        [Column {:width 12}
         [Radio {:style {:float "left" :margin-top "0.90rem"}
                 :checked (not @public-access?)
                 :disabled (not owner-has-pro?)
                 :on-click (when owner-has-pro?
                             #(reset! public-access? false))}]
         [:div {:style (cond-> {:float "left" :margin-left "0.5rem"}
                         (not owner-has-pro?) (assoc :color "grey"))}
          [Icon {:name "lock" :size "huge"
                 :style {:float "left" :margin-top "0.5rem"}}]
          [:div {:style {:float "left" :margin-top "0.25rem" :margin-left "0.5rem"}}
           [:p {:style {:font-size "1.9rem" :margin-bottom "0.25rem"}}
            "Private"]
           [:p {:style {:margin-top "0.25rem"}}
            "Only people you've invited to the project can see it."]
           [:p {:style {:margin-top "0.25rem"}}
            "Private Projects are only available for "
            [:a {:href "/pricing"} "Pro Accounts"]
            "."]]]]]]
      [Divider]
      [Button {:type :submit :positive true
               :disabled (or (action/running?)
                             (data/loading?)
                             (empty? @project-name))
               :loading (or (action/running?)
                            (data/loading?))} "Create Project"]]]))

(def-panel :uri "/new" :panel panel
  :on-route (let [user-id @(subscribe [:self/user-id])]
              (when user-id
                (dispatch [:reload [:user/current-plan user-id]])
                (dispatch [:reload [:user/orgs user-id]]))
              (dispatch [:set-active-panel panel])
              (dispatch [::set :project-owner
                         (-> (some-> (util/get-url-params) :project_owner
                                     parse-integer)
                             (or "current-user"))])
              (dispatch [::set :project-name nil])
              (dispatch [::set :public-access true]))
  :content (when-let [user-id @(subscribe [:self/user-id])]
             (with-loader [[:user/current-plan user-id]
                           [:user/orgs user-id]] {}
               [CreateProject]))
  :require-login true)
