(ns sysrev.views.panels.user.invitations
  (:require [clojure.string :as str]
            [ajax.core :refer [GET PUT]]
            [cljsjs.moment]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe reg-event-fx reg-sub dispatch]]
            [re-frame.db :refer [app-db]]
            [sysrev.util :refer [vector->hash-map]]
            [sysrev.views.semantic :refer [Segment Button Message MessageHeader Grid Row Column]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:user :invitations])

(def state (r/cursor app-db [:state :panels panel]))

(reg-sub :user/invitations
         (fn [db] @(r/cursor state [:invitations])))

(defn get-invitations!
  "Get the invitations that have been sent to this user"
  []
  (let [user-id @(subscribe [:self/user-id])
        invitations (r/cursor state [:invitations])
        getting-invitations? (r/cursor state [:getting-invitations?])]
    (reset! getting-invitations? true)
    (GET (str "/api/user/" user-id "/invitations")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! invitations (-> response :result :invitations (vector->hash-map :id)))
                     (reset! getting-invitations? false))
          :error-handler (fn [error]
                           (reset! getting-invitations? false)
                           ($ js/console log "[get-invitations!] There was an error"))})))

(reg-event-fx
 :user/get-invitations!
 []
 (fn [_ _]
   (get-invitations!)
   {}))

(defn Invitation
  [{:keys [id project-id project-name description accepted active created updated]}]
  (let [putting-invitation? (r/cursor state [:invitation id :putting?])
        error-message (r/atom "")
        put-invitation! (fn [id accepted?]
                          (reset! putting-invitation? true)
                          (PUT (str "/api/user/" @(subscribe [:self/user-id]) "/invitation/" id)
                               {:params {:accepted accepted?}
                                :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                :handler (fn [response]
                                           (reset! putting-invitation? false)
                                           (reset! (r/cursor state [:invitations id :accepted]) accepted?)
                                           (get-invitations!))
                                :error-handler (fn [error-response]
                                                 (reset! putting-invitation? false)
                                                 (reset! error-message (get-in error-response [:response :error :message])))}))]
    
    [Segment
     [Grid
      [Row
       [Column {:width 8}
        [:h3 project-name]]
       [Column {:width 8}
        [:h5 {:style {:text-align "right"}} (-> created js/moment ($ format "YYYY-MM-DD h:mm A"))]]]
      [Row
       [Column {:width 16}
        (when-not (nil? accepted)
          [:div (str "You "
                     (if accepted
                       "accepted "
                       "declined ")
                     "this invitation on "
                     (-> updated js/moment ($ format "YYYY-MM-DD"))
                     " at "
                     (-> updated js/moment ($ format "h:mm A")))])]]]
     (when (nil? accepted)
       [:div
        [:h3 (str "You've been invited as a " description ".")]
        [Button {:on-click #(put-invitation! id true)
                 :basic true
                 :color "green"
                 :disabled @putting-invitation?
                 :size "mini"} "Accept"]
        [Button {:on-click #(put-invitation! id false)
                 :basic true
                 :color "red"
                 :disabled @putting-invitation?
                 :size "mini"} "Decline"]])
     (when-not (str/blank? @error-message)
       [Message {:onDismiss #(reset! error-message nil)
                 :negative true}
        [MessageHeader "Invitation Error"]
        @error-message])]))

(defn Invitations
  []
  (let [getting-invitations? (r/cursor state [:getting-invitations?])
        invitations (r/cursor state [:invitations])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         (if-not (empty? @invitations)
           (map (fn [invitation]
                  ^{:key (:id invitation)}
                  [Invitation invitation])
                (->> (vals @invitations)
                     ;;(filter #(nil? (:accepted %)))
                     ))
           [Segment "You don't currently have any invitations to other projects"])])
      :get-initial-state
      (fn [this]
        (when-not @getting-invitations?
          (dispatch [:user/get-invitations!])))})))

