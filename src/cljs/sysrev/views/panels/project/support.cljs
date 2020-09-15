(ns sysrev.views.panels.project.support
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe]]
            [sysrev.accounting :as accounting]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer
             [Form FormGroup FormInput FormRadio]]
            [sysrev.stripe :as stripe]
            [sysrev.util :as util :refer [in? ensure-prefix wrap-prevent-default]]
            [sysrev.macros :refer-macros [setup-panel-state]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:project :project :support] {:state-var state})

(defn get-user-support-subscriptions
  "Get the current support subscriptions for user"
  []
  (GET "/api/user-support-subscriptions"
       {:handler (fn [response]
                   (reset! (r/cursor state [:user-support-subscriptions])
                           (:result response)))}))

(defn get-current-support
  "Get the current support level"
  []
  (GET (str "/api/current-support")
       {:params {:project-id @(subscribe [:active-project-id])}
        :handler (fn [response]
                   (let [{:keys [quantity]} (:result response)
                         current-support-level (r/cursor state [:current-support-level])
                         support-level (r/cursor state [:support-level "monthly"])
                         user-support-level (r/cursor state [:user-support-level])]
                     (reset! current-support-level quantity)
                     (cond
                       ;; the support level is at a fixed amount
                       (in? [500 1000 5000] @current-support-level)
                       (do (reset! support-level quantity)
                           (reset! user-support-level (accounting/cents->string 100)))
                       ;; the support level is at variable amount
                       @current-support-level
                       (do (reset! support-level :user-defined)
                           (reset! user-support-level (accounting/cents->string quantity)))
                       ;; quantity is nil, but default something for support-level
                       ;; but only if the support-level is currently nil
                       (and (nil? @current-support-level)
                            (nil? @support-level))
                       (reset! support-level 500))))}))

(def-action :support/support-plan
  :uri (fn [] "/api/support-project")
  :content (fn [amount frequency]
             {:amount (int amount)
              :project-id @(subscribe [:active-project-id])
              :frequency frequency})
  :on-error (fn [{:keys [error]} [_amount frequency] _]
              (let [{:keys [message type]} error]
                ;; do we need a card?
                (when (in? ["This customer has no attached payment source"
                            "Your card was declined"
                            "Cannot charge a customer that has no active card"]
                           message)
                  (reset! (r/cursor stripe/state [:need-card?]) true))
                (reset! (r/cursor state [:error-message frequency])
                        (cond
                          (in? ["This customer has no attached payment source"
                                "Cannot charge a customer that has no active card"]
                               message)
                          "You must provide a valid payment method"
                          (= type "already_supported_at_amount")
                          (str "You are already supporting at "
                               (-> message :amount accounting/cents->string))
                          (= type "amount_too_low")
                          (str "Minimum support level is "
                               (-> message :minimum accounting/cents->string)
                               (when (= frequency "monthly")
                                 " per month"))
                          :else message)))
              (reset! (r/cursor state [:loading?]) false)
              {})
  :process (fn [_ _ {:keys [success]}]
             (let [user-defined-support-level (r/cursor state [:user-defined-support-level])]
               (reset! user-defined-support-level "$1.00")
               (reset! (r/cursor state [:loading?]) false)
               (reset! (r/cursor state [:error-message]) nil)
               (get-current-support)
               (dispatch [:project/get-funds])
               {})))

;; TODO: def-action functions run in re-frame event, shouldn't call subscribe
(def-action :support/cancel
  :uri (fn [] "/api/cancel-project-support")
  :content (fn [] {:project-id @(subscribe [:active-project-id])})
  :process (fn [_ _ {:keys [success]}]
             (let [confirming-cancel? (r/cursor state [:confirming-cancel?])
                   loading? (r/cursor state [:loading?])]
               (get-current-support)
               (reset! confirming-cancel? false)
               (reset! loading? false)
               {})))

(defn SupportFormMonthly [state]
  (let [support-level (r/cursor state [:support-level "monthly"])
        user-support-level (r/cursor state [:user-support-level-monthly])
        error-message (r/cursor state [:error-message "monthly"])
        need-card? (r/cursor stripe/state [:need-card?])
        loading? (r/cursor state [:loading?])
        current-support-level (r/cursor state [:current-support-level])
        confirming-cancel? (r/cursor state [:confirming-cancel?])
        frequency "monthly"]
    [:div.ui.segment
     (if @current-support-level
       [:h1 "Change Your Level of Support"]
       [:h1 "Support This Project"])
     (when @current-support-level
       [:h3.support-message
        (str "You are currently supporting this project at "
             (accounting/cents->string @current-support-level) " per month")])
     [Form {:on-submit
            #(let [cents (accounting/string->cents @user-support-level)]
               (cond (and (= @support-level :user-defined)
                          (= cents 0))
                     (reset! user-support-level "$0.00")
                     (and (= @support-level :user-defined)
                          (> cents 0))
                     (do (reset! loading? true)
                         (dispatch [:action [:support/support-plan cents frequency]]))
                     :else
                     (do (reset! loading? true)
                         (dispatch
                          [:action [:support/support-plan @support-level frequency]]))))}
      [FormGroup
       [FormRadio {:label "$5 per month"
                   :checked (= @support-level 500)
                   :on-change #(reset! support-level 500)}]]
      [FormGroup
       [FormRadio {:label "$10 per month"
                   :checked (= @support-level 1000)
                   :on-change #(reset! support-level 1000)}]]
      [FormGroup
       [FormRadio {:label "$50 per month"
                   :checked (= @support-level 5000)
                   :on-change #(reset! support-level 5000)}]]
      [FormGroup
       [FormRadio {:checked (= @support-level :user-defined)
                   :on-change #(reset! support-level :user-defined)}]
       [:div
        [FormInput {:value @user-support-level
                    :on-change (util/on-event-value
                                #(reset! user-support-level (ensure-prefix % "$")))
                    :on-click #(reset! support-level :user-defined)}]
        " per month"]]
      (when-not @confirming-cancel?
        [:div.field
         (when-not @need-card?
           [:button.ui.primary.button
            {:class (when @loading? "disabled")}
            "Continue"])
         (when @need-card?
           [:div.ui.primary.button.update-payment
            {:on-click
             (wrap-prevent-default
              #(do (dispatch [:stripe/set-calling-route!
                              (project-uri @project-uri "/support")])
                   (dispatch [:navigate [:payment]])))}
            "Update Payment Information"])
         (when-not (nil? @current-support-level)
           [:button.ui.button
            {:class (when @loading? "disabled")
             :on-click (wrap-prevent-default
                        #(do (reset! error-message nil)
                             (reset! confirming-cancel? true)))}
            "Cancel Support"])])
      (when @confirming-cancel?
        [:div
         [:h3.ui.red.header
          "Are you sure want to end your support for this project?"]
         [:div.field
          [:button.ui.green.button
           {:class (when @loading? "disabled")
            :on-click (wrap-prevent-default #(reset! confirming-cancel? false))}
           "Continue to Support"]
          [:button.ui.red.button
           {:class (when @loading? "disabled")
            :on-click (wrap-prevent-default
                       #(do (reset! loading? true)
                            (dispatch [:action [:support/cancel]])))}
           "Stop Support"]]])
      (when @error-message
        [:div.ui.red.header @error-message])]]))

(defn ^:unused SupportFormOnce [state]
  (let [support-level (r/cursor state [:support-level "once"])
        user-defined-support-level (r/cursor state [:user-defined-support-level])
        error-message (r/cursor state [:error-message "once"])
        need-card? (r/cursor stripe/state [:need-card?])
        loading? (r/cursor state [:loading?])
        frequency "once"
        project-id (subscribe [:active-project-id])]
    (r/create-class
     {:reagent-render
      (fn [_]
        [:div.ui.segment
         [:h4.ui.dividing.header "Add Funds"]
         [Form {:on-submit
                #(let [cents (accounting/string->cents @user-defined-support-level)]
                   (cond (and (= @support-level :user-defined)
                              (= cents 0))
                         (reset! user-defined-support-level "$0.00")
                         (and (= @support-level :user-defined)
                              (> cents 0))
                         (do (reset! loading? true)
                             (dispatch [:action [:support/support-plan cents frequency]]))
                         :else
                         (do (reset! loading? true)
                             (dispatch
                              [:action [:support/support-plan @support-level frequency]]))))}
          [FormGroup
           [FormInput {:id "paypal-amount"
                       :value @user-defined-support-level
                       :on-change (util/on-event-value
                                   #(reset! user-defined-support-level
                                            (ensure-prefix % "$")))
                       :on-click #(reset! support-level :user-defined)}]]
          [:div.field
           (when-not @need-card?
             [:button.ui.primary.button {:class (when @loading? "disabled")}
              "Continue"])
           (when @need-card?
             [:div.ui.primary.button.update-payment
              {:on-click
               (wrap-prevent-default
                #(do (dispatch [:stripe/set-calling-route!
                                (project-uri @project-id "/compensations")])
                     (dispatch [:navigate [:payment]])))}
              "Update Payment Information"])]
          (when @error-message
            [:div.ui.red.header @error-message])]])
      :get-initial-state
      (fn [_this]
        (reset! support-level :user-defined)
        (reset! user-defined-support-level "$1.00")
        (reset! loading? false)
        (reset! need-card? false)
        (reset! error-message "")
        {})})))

(defn Support []
  (get-current-support)
  [SupportFormMonthly state])

(defn UserSupportSubscriptions []
  (let [user-subs (r/cursor state [:user-support-subscriptions])]
    (get-user-support-subscriptions)
    (when-not (empty? @user-subs)
      [:div.ui.segment
       [:h3 "Thank You For Supporting These Projects"]
       [:table.ui.striped.table
        [:thead
         [:tr [:th "Project"] [:th "Amount"] [:th]]]
        [:tbody
         (doall
          (for [{:keys [name project-id quantity]} @user-subs]
            ^{:key project-id}
            [:tr
             [:td name]
             [:td (accounting/cents->string quantity)]
             [:td [:a {:href (project-uri project-id "/support")}
                   "Change Your Level of Support"]]]))]]])))

(defmethod panel-content panel []
  (fn [_child]
    (when (nil? @(r/cursor state [:user-support-level-monthly]))
      (reset! (r/cursor state [:user-support-level-monthly]) "$1.00"))
    (reset! (r/cursor state [:error-message "monthly"]) "")
    (reset! (r/cursor state [:error-message "once"]) "")
    (reset! (r/cursor state [:loading?]) false)
    (reset! (r/cursor state [:confirming-cancel?]) false)
    [Support]))
