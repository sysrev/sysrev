(ns sysrev.views.components.clone-project
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-fx trim-v]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.views.panels.user.profile :refer [Avatar]]
            [sysrev.views.semantic :refer
             [Button Icon Modal ModalHeader ModalContent ModalDescription Loader Dimmer]]))

(def-action :clone-project-user
  :uri (fn [_] "/api/clone-project")
  :content (fn [src-project-id] {:src-project-id src-project-id})
  :process (fn [_ _ {:keys [message dest-project-id]}]
             (if dest-project-id
               {:dispatch-n
                (list [:reload [:identity]]
                      [::modal-open? false]
                      [::cloning? false]
                      [:project/navigate dest-project-id])}
               ;; TODO: do something on error
               {}))
  :on-error (fn [_ _ {:keys [message]}]
              {:dispatch-n
               (list [::modal-open? false]
                     [::cloning? false])}))

(def-action :clone-project-org
  :uri (fn [_ org-id] (str "/api/org/" org-id "/project/clone"))
  :content (fn [src-project-id _] {:src-project-id src-project-id})
  :process (fn [_ _ {:keys [message dest-project-id]}]
             (if dest-project-id
               {:dispatch-n
                (list [:reload [:identity]]
                      [::modal-open? false]
                      [::cloning? false]
                      [:project/navigate dest-project-id])}
               {}))
  :on-error (fn [_ _ {:keys [message]}]
              {:dispatch-n
               (list [::modal-open? false]
                     [::cloning? false])}))

(reg-sub ::modal-open?
         (fn [db _]
           (get db ::modal-open?)))

(reg-event-fx
 ::modal-open?
 [trim-v]
 (fn [{:keys [db]} [value]]
   {:db (assoc db ::modal-open? value)}))

(reg-sub ::cloning?
         (fn [db _]
           (get db ::cloning?)))

(reg-event-fx
 ::cloning?
 [trim-v]
 (fn [{:keys [db]} [value]]
   {:db (assoc db ::cloning? value)}))

(defn CloneProject []
  (let [modal-open (subscribe [::modal-open?])
        project-name (subscribe [:project/name])
        project-id (subscribe [:active-project-id])
        user-id (subscribe [:self/user-id])
        orgs (subscribe [:self/orgs])
        cloning? (subscribe [::cloning?])]
    [Modal {:class "clone-project"
            :open @modal-open
            :on-open #(dispatch [::modal-open? true])
            :on-close #(when-not @cloning?
                         (dispatch [::modal-open? false]))
            :trigger (r/as-component
                      [Button {:size "tiny"
                               :class "project-access"
                               :style {:margin-right "0.25rem"}}
                       [Icon {:name "clone"}] "Clone"])
            :size "tiny"}
     [ModalHeader (str "Clone " @project-name "")]
     [ModalContent
      [Dimmer {:active @cloning?}
       [Loader
        ;; hack due to semantic.css not working
        ;; properly inside of modal
        (when @cloning?
          {:style {:display "block"}})
        "Cloning Project"]]
      [ModalDescription [:h2 {:style {:text-align "center"
                                      :margin-bottom "2rem"}}
                         (str "Where should we clone " @project-name "?")]]
      [:div {:on-click (fn [_]
                         (dispatch [::cloning? true])
                         (dispatch [:action [:clone-project-user @project-id]]))
             :style {:cursor "pointer"}}
       [Avatar {:user-id @user-id}]
       [:a {:style {:font-size "1.25rem"}} @(subscribe [:user/display])]]
      (when (and (seq (->> @orgs (filter #(some #{"owner" "admin"} (:permissions %))))))
        (for [org @orgs]
          ^{:key (str "org-id-" (:group-id org))}
          [:div {:on-click (fn [_]
                             (dispatch [::cloning? true])
                             (dispatch [:action [:clone-project-org @project-id (:group-id org)]]))
                 :style {:cursor "pointer"
                         :margin-top "1rem"}}
           [Icon {:name "group"
                  :style {:display "inline"
                          :margin-right "0.25em"}
                  :size "large"}]
           [:a {:style {:font-size "1.25rem"}} (:group-name org)]]))]]))
