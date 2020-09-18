(ns sysrev.views.panels.new-project
  (:require [medley.core :as medley :refer [find-first]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav :refer [nav-scroll-top get-url-params]]
            [sysrev.stripe :as stripe]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.views.base :refer [logged-out-content]]
            [sysrev.views.semantic :refer [Divider Dropdown Form Grid Input Row Column
                                           Button Icon Radio]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:new-project] {:state-var state
                                         :get-fn panel-get :set-fn panel-set
                                         :get-sub ::get :set-event ::set})

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
           :on-click #(if (integer? project-owner)
                        (nav-scroll-top "/new" :params {:project_owner project-owner})
                        (nav-scroll-top "/new"))}
   [Icon {:name "list alternate outline"}] "New"])

(defn- OwnerDropdown []
  (let [project-owner (r/cursor state [:project-owner])
        options (cons {:text @(subscribe [:user/display]) :value "current-user"}
                      (for [{:keys [group-name group-id]}
                            (filter #(some #{"owner" "admin"} (:permission %))
                                    @(subscribe [:self/orgs]))]
                        {:text group-name :value group-id}))]
    [:div {:style {:margin-top "0.5em"}}
     [Dropdown {:fluid true
                :options options
                :value (or @project-owner "current-user")
                :on-change (fn [_event data]
                             (reset! project-owner (.-value data)))}]]))

(defn- CreateProject []
  (let [project-name (r/cursor state [:project-name])
        project-owner (r/cursor state [:project-owner])
        public-access? (r/cursor state [:public-access])
        orgs @(subscribe [:self/orgs])
        plan (if (= @project-owner "current-user")
               @(subscribe [:user/current-plan])
               (:plan (->> orgs (find-first #(= @project-owner (:group-id %))))))
        owner-has-pro? (contains? stripe/pro-plans (:nickname plan))]
    [Form {:id "create-project-form"
           :on-submit #(when @project-name
                         (if (= @project-owner "current-user")
                           (run-action :create-project
                                       @project-name @public-access?)
                           (run-action :create-org-project
                                       @project-name @project-owner @public-access?)))}
     [:div
      [:div {:id "create-project-form-header"}
       [:p {:style {:font-size "1.9rem" :margin-bottom "0.5rem"}}
        "Create a new project"]
       [:p "A project contains articles that are labeled by reviewers."]]
      [Divider]
      [:div {:id "create-project-form-owner-name"}
       [Grid {:doubling true}
        [Row
         [Column {:width (if (util/mobile?) 6 3)}   [:p "Owner"]]
         [Column {:width (if (util/mobile?) 10 5)}  [:p "Project Name"]]]
        [Row [Column {:width (if (util/mobile?) 6 3)}
              [:div.inline-block {:style {:width (if (util/mobile?) "80%" "88%")}}
               [OwnerDropdown]]
              [:span.bold {:style {:font-size "1.5em" :margin-left "1rem"}}
               "/"]]
         [Column {:text-align "left" :width (if (util/mobile?) 10 5)}
          [Input {:placeholder "Project Name"
                  :class "project-name"
                  :fluid true
                  :on-change (util/on-event-value #(reset! project-name %))}]]]]]
      [Divider]
      [:div {:id "create-project-form-public-or-private"}
       [Grid {:doubling true}
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
             "."]]]]]]]
      [Divider]
      [:div {:id "create-project-form-submit-button"}
       [Button {:positive true
                :disabled (or (loading/any-action-running?)
                              (loading/any-loading?)
                              (empty? @project-name))
                :loading (or (loading/any-action-running?)
                             (loading/any-loading?))}
        "Create Project"]]]]))

(def-panel {:panel panel
            :uri "/new"
            :on-route (let [user-id @(subscribe [:self/user-id])]
                        (when user-id
                          (dispatch [:reload [:user/current-plan user-id]])
                          (dispatch [:reload [:user/orgs user-id]]))
                        (dispatch [:set-active-panel panel])
                        (dispatch [::set :project-owner
                                   (-> (some-> (get-url-params) :project_owner parse-integer)
                                       (or "current-user"))])
                        (dispatch [::set :project-name nil])
                        (dispatch [::set :public-access true]))
            :content (when-let [user-id @(subscribe [:self/user-id])]
                       (with-loader [[:user/current-plan user-id]
                                     [:user/orgs user-id]] {}
                         [CreateProject]))
            :logged-out-content (logged-out-content :logged-out)})
