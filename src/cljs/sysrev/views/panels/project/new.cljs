(ns sysrev.views.panels.project.new
  (:require [medley.core :as medley]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav :refer [nav-scroll-top]]
            [sysrev.stripe :as stripe]
            [sysrev.util :as util]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.semantic :refer [Divider Dropdown Form FormRadio FormGroup Grid Input Row Column Button Icon Radio]]))

(defonce state (r/atom {}))

(def-action :create-project
  :uri (fn [_] "/api/create-project")
  :content (fn [project-name public-access?] {:project-name project-name
                                              :public-access public-access?})
  :process (fn [_ _ {:keys [success message project]}]
             (if success
               {:dispatch-n
                (list [:reload [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

(def-action :create-org-project
  :uri (fn [_ org-id] (str "/api/org/" org-id "/project"))
  :content (fn [project-name _ public-access?] {:project-name project-name
                                                :public-access public-access?})
  :process (fn [_ _ {:keys [success message project]}]
             (if success
               {:dispatch-n
                (list [:reload [:identity]]
                      [:project/navigate (:project-id project)])}
               ;; TODO: do something on error
               {})))

(defn NewProjectButton
  [& [{:keys [project-owner]}]]
  [Button {:size "small"
           :id "new-project"
           :positive true
           :on-click #(if (integer? project-owner)
                        (nav-scroll-top "/new" :params {:project_owner project-owner})
                        (nav-scroll-top "/new"))}
   [Icon {:name "list alternate outline"}] "New"])

(defn OwnerDropdown []
  (let [project-owner (r/cursor state [:project-owner])
        orgs (subscribe [:self/orgs])
        options (fn [orgs]
                  (->> orgs
                       ;; get the orgs you have permission to modify
                       (filter #(some #{"owner" "admin"} (:permissions %)))
                       (map #(hash-map :text (:group-name %)
                                       :value (:group-id %)))
                       (cons {:text @(subscribe [:user/display])
                              :value "current-user"})))]
    (r/create-class
     {:reagent-render (fn [_]
                        [:div {:style {:margin-top "0.5em"}}
                         [Dropdown {:fluid true
                                    :options (options @orgs)
                                    :value @project-owner
                                    :on-change (fn [_event data]
                                                 (reset! project-owner (.-value data)))}]])
      :get-initial-state (fn [_]
                           (when (nil? @project-owner)
                             (reset! project-owner "current-user")))})))

(defn CreateProject []
  (let [project-name (r/cursor state [:project-name])
        project-owner (r/cursor state [:project-owner])
        public-access? (r/cursor state [:public-access])
        current-plan (subscribe [:user/current-plan])
        user-id (subscribe [:self/user-id])
        orgs (subscribe [:self/orgs])]
    (r/create-class
     {:reagent-render
      (fn [_]
        (let [owner-has-pro? (if (= @project-owner "current-user")
                               (boolean (contains? stripe/pro-plans (:nickname @current-plan)))
                               (boolean (contains? stripe/pro-plans (->> @orgs
                                                                         (medley.core/find-first #(= (:group-id %)
                                                                                                     @project-owner))
                                                                         :plan
                                                                         :nickname))))]
          [Form {:id "create-project-form"
                 :on-submit  #(if-not (nil? @project-name)
                                (if (= @project-owner "current-user")
                                  (dispatch [:action [:create-project @project-name @public-access?]])
                                  (dispatch [:action [:create-org-project @project-name @project-owner @public-access?]])))}
           [:div
            [:div {:id "create-project-form-header"}
             [:p {:style {:font-size "1.9rem"
                          :margin-bottom "0.5rem"}} "Create a new project"]
             [:p "A project contains articles that are labeled by reviewers."]]
            [Divider]
            [:div {:id "create-project-form-owner-name"}
             [Grid {:doubling true}
              [Row
               [Column {:width (if (util/mobile?) 6 3)}
                [:p "Owner"]]
               [Column {:width (if (util/mobile?) 10 5)}
                [:p "Project Name"]]]
              [Row [Column {:width (if (util/mobile?) 6 3)}
                    [:div {:style {:display "inline-block"
                                   :width (if (util/mobile?)
                                            "80%"
                                            "88%")}}
                     [OwnerDropdown]]
                    [:span {:style {:font-size "1.5em"
                                    :margin-left "1rem"}
                            :class "bold"} "/"]]
               [Column {:width (if (util/mobile?) 10 5)
                        :text-align "left"}
                [Input {:placeholder "Project Name"
                        :class "project-name"
                        :fluid true
                        :on-change (util/on-event-value #(reset! project-name %))}]]]]]
            [Divider]
            [:div {:id "create-project-form-public-or-private"}
             [Grid {:doubling true}
              [Row [Column {:width 12}
                    [Radio {:style
                            {:float "left"
                             :margin-top "0.90rem"}
                            :checked @public-access?
                            :on-click (fn [_]
                                        (reset! public-access? true))}]
                    [:div {:style {:float "left"
                                   :margin-left "0.5rem"}}
                     [Icon {:name "list alternate outline"
                            :size "huge"
                            :style {:float "left"
                                    :margin-top "0.5rem"}}]
                     [:div {:style {:float "left"
                                    :margin-top "0.25rem"
                                    :margin-left "0.5rem"}}
                      [:p {:style {:font-size "1.9rem"
                                   :margin-bottom "0.25rem"}}  "Public"]
                      [:p {:style {:margin-top "0.25rem"}}
                       "Anyone on the internet can see this project."]]]]]
              [Row
               [Column {:width 12}
                [Radio {:style {:float "left"
                                :margin-top "0.90rem"}
                        :checked (not @public-access?)
                        :disabled (not owner-has-pro?)
                        :on-click (fn [_]
                                    (when owner-has-pro?
                                      (reset! public-access? false)))}]
                [:div {:style (cond-> {:float "left"
                                       :margin-left "0.5rem"}
                                (not owner-has-pro?)
                                (assoc :color "grey"))}
                 [Icon {:name "lock"
                        :size "huge"
                        :style {:float "left"
                                :margin-top "0.5rem"}}]
                 [:div {:style {:float "left"
                                :margin-top "0.25rem"
                                :margin-left "0.5rem"}}
                  [:p {:style {:font-size "1.9rem"
                               :margin-bottom "0.25rem"}} "Private"]
                  [:p {:style {:margin-top "0.25rem"}}
                   "Only people you've invited to the project can see it."]
                  [:p {:style {:margin-top "0.25rem"}}
                   "Private Projects are only available for " [:a {:href "/pricing"} "Pro Accounts"] "."]]]]]]]
            [Divider]
            [:div {:id "create-project-form-submit-button"}
             [Button {:positive true
                      :disabled (if (or (loading/any-action-running?)
                                        (loading/any-loading?))
                                  true
                                  (if (not (seq @project-name))
                                    true
                                    false))
                      :loading (or (loading/any-action-running?)
                                   (loading/any-loading?))}
              "Create Project"]]]]))
      :get-initial-state (fn [_]
                           (let [owner-id (-> (nav/get-url-params) :project_owner util/parse-integer)]
                             (if (integer? owner-id)
                               (reset! project-owner owner-id)
                               (reset! project-owner "current-user")))
                           (dispatch [:data/load [:user/current-plan @user-id]])
                           (dispatch [:fetch [:user/orgs @user-id]])
                           (reset! project-name nil)
                           (reset! public-access? true)
                           nil)})))

(defmethod panel-content [:new] []
  (fn [_]
    [CreateProject]))

(defmethod logged-out-content [:new] []
  (logged-out-content :logged-out))
