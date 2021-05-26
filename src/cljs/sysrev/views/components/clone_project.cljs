(ns sysrev.views.components.clone-project
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.action.core :as action :refer [def-action run-action]]
            [sysrev.nav :refer [nav make-url]]
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
                      [:project/navigate dest-project-id])}
               ;; TODO: do something on error
               {}))
  :on-error (fn [_ _ {:keys [message]}]
              {:dispatch [::modal-open? false]}))

(def-action :clone-project-org
  :uri (fn [_ org-id] (str "/api/org/" org-id "/project/clone"))
  :content (fn [src-project-id _] {:src-project-id src-project-id})
  :process (fn [_ _ {:keys [message dest-project-id]}]
             (if dest-project-id
               {:dispatch-n
                (list [:reload [:identity]]
                      [::modal-open? false]
                      [:project/navigate dest-project-id])}
               {}))
  :on-error (fn [_ _ {:keys [message]}]
              {:dispatch [::modal-open? false]}))

(reg-sub ::modal-open? (fn [db] (get db ::modal-open?)))

(reg-event-db ::modal-open? [trim-v]
              (fn [db [value]]
                (assoc db ::modal-open? value)))

(defn CloneProject []
  (let [modal-open @(subscribe [::modal-open?])
        project-name @(subscribe [:project/name])
        project-id @(subscribe [:active-project-id])
        user-id @(subscribe [:self/user-id])
        orgs @(subscribe [:self/orgs])
        cloning? (action/running? #{:clone-project-user
                                    :clone-project-org})
        redirect-url (-> @(subscribe [:project/uri]) (make-url {:cloning true}))]
    (when (or @(subscribe [:project/public-access?])
              @(subscribe [:member/admin? true])) ; render if user is allowed to clone
      [Modal {:class "clone-project"
              :open modal-open
              :on-open #(if user-id
                          (dispatch [::modal-open? true])
                          (nav "/register"
                               :params
                               {:redirect redirect-url
                                :redirect_message "First, create an account to clone the project"}))
              :on-close #(when-not cloning?
                           (dispatch [::modal-open? false]))
              :trigger (r/as-element
                        [Button {:id "clone-button" :class "project-access"
                                 :size "tiny" :style {:margin-right "0.25rem"}}
                         [Icon {:name "clone"}] "Clone"])
              :size "tiny"}
       [ModalHeader (str "Clone " project-name "")]
       [ModalContent
        [Dimmer {:active (boolean cloning?)}
         [Loader (when cloning?
                   ;; hack due to semantic.css not working
                   ;; properly inside of modal
                   {:style {:display "block"}})
          "Cloning Project"]]
        [ModalDescription [:h2 {:style {:text-align "center" :margin-bottom "2rem"}}
                           (str "Where should we clone \"" project-name "\"?")]]
        [:div {:id "clone-to-user"
               :on-click #(run-action :clone-project-user project-id)
               :style {:cursor "pointer"}}
         [Avatar {:user-id user-id}]
         [:a {:style {:font-size "1.25rem"}} @(subscribe [:user/username])]]
        ;; TODO: orgs shown should be filtered by this?
        (when (seq (->> orgs (filter #(some #{"owner" "admin"} (:permissions %)))))
          (for [org orgs]
            ^{:key (str "org-id-" (:group-id org))}
            [:div {:on-click #(run-action :clone-project-org project-id (:group-id org))
                   :style {:cursor "pointer" :margin-top "1rem"}}
             [Icon {:name "group" :size "large"
                    :style {:display "inline" :margin-right "0.25em"}}]
             [:a {:style {:font-size "1.25rem"}} (:group-name org)]]))]])))
