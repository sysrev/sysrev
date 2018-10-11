(ns sysrev.views.panels.project.support
  (:require [ajax.core :refer [GET]]
            [cljsjs.accounting]
            [cljsjs.semantic-ui-react]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.accounting :as accounting]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer [Form FormButton FormField FormGroup FormInput FormRadio Label]]
            [sysrev.stripe :as stripe])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def panel [:project :project :support])

(def state (r/cursor app-db [:state :panels panel]))

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
                       (some #(= @current-support-level
                                 %) [500 1000 5000])
                       (do
                         (reset! support-level quantity)
                         (reset! user-support-level (accounting/cents->string 100)))

                       ;; the support level is at variable amount
                       (not (nil? @current-support-level))
                       (do
                         (reset! support-level :user-defined)
                         (reset! user-support-level (accounting/cents->string quantity)))
                       ;; quantity is nil, but default something for support-level
                       ;; but only if the support-level is currently nil
                       (and (nil? @current-support-level)
                            (nil? @support-level))
                       (do (reset! support-level 500)))))}))

(def-action :support/support-plan
  :uri (fn [] "/api/support-project")
  :content (fn [amount frequency]
             {:amount amount
              :project-id @(subscribe [:active-project-id])
              :frequency frequency})
  :on-error (fn [{:keys [db error]} [amount frequency] _]
              ;; do we need a card?
              (when (some (partial = (-> error :message))
                          ["This customer has no attached payment source"
                           "Your card was declined"])
                (reset! (r/cursor stripe/state [:need-card?]) true))
              (cond
                (= (-> error :message) "This customer has no attached payment source")
                (reset! (r/cursor state [:error-message frequency])
                        "You must provide a valid payment method")
                (= (-> error :type) "already_supported_at_amount")
                (reset! (r/cursor state [:error-message frequency])
                        (str "You are already supporting at "
                             (accounting/cents->string (-> error :message :amount))))
                (= (-> error :type) "amount_too_low")
                (reset! (r/cursor state [:error-message frequency])
                        (str "Minimum support level is "
                             (accounting/cents->string (-> error :message :minimum))
                             (when (= frequency "monthly")
                               " per month")))
                :else
                (reset! (r/cursor state [:error-message frequency]) (-> error :message)))
              (reset! (r/cursor state [:loading?]) false)
              {})
  :process
  (fn [{:keys [db]} _ {:keys [success] :as result}]
    (let [support-level (r/cursor state [:support-level])
          user-support-level (r/cursor state [:user-support-level])]
      (reset! (r/cursor state [:loading?]) false)
      (reset! (r/cursor state [:error-message]) nil)
      (get-current-support)
      {})))

(def-action :support/cancel
  :uri (fn [] "/api/cancel-project-support")
  :content (fn []
             {:project-id @(subscribe [:active-project-id])})
  :process (fn [{:keys [db]} _ {:keys [success] :as result}]
             (let [confirming-cancel? (r/cursor state [:confirming-cancel?])
                   loading? (r/cursor state [:loading?])]
               (get-current-support)
               (reset! confirming-cancel? false)
               (reset! loading? false)
               {})))

(defn SupportFormMonthly
  [state]
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
       [:h3.support-message (str "You are currently supporting this project at " (accounting/cents->string @current-support-level) " per month")])
     [Form {:on-submit
            (fn []
              (let [cents (accounting/string->cents @user-support-level)]
                (cond (and (= @support-level
                              :user-defined)
                           (= cents
                              0))
                      (reset! user-support-level "$0.00")
                      (and (= @support-level
                              :user-defined)
                           (> cents
                              0))
                      (do
                        (reset! loading? true)
                        (dispatch [:action [:support/support-plan cents frequency]]))
                      :else
                      (do
                        (reset! loading? true)
                        (dispatch [:action [:support/support-plan @support-level frequency]])))))}
      [FormGroup
       [FormRadio {:label "$5 per month"
                   :checked (= @support-level
                               500)
                   :on-change #(reset! support-level 500)}]]
      [FormGroup
       [FormRadio {:label "$10 per month"
                   :checked (= @support-level
                               1000)
                   :on-change #(reset! support-level 1000)}]]
      [FormGroup
       [FormRadio {:label "$50 per month"
                   :checked (= @support-level
                               5000)
                   :on-change #(reset! support-level 5000)}]]
      [FormGroup
       [FormRadio {:checked (= @support-level
                               :user-defined)
                   :on-change #(reset! support-level :user-defined)}]
       [:div
        (let [on-change (fn [event]
                          (let [value ($ event :target.value)
                                dollar-sign-on-front? (fn [value]
                                                        (= ($ value indexOf "$")
                                                           0))
                                new-value (cond
                                            (not (dollar-sign-on-front? value))
                                            (str "$" ($ event :target.value))
                                            :else
                                            value)]
                            (reset! user-support-level new-value)))]
          [FormInput {:value @user-support-level
                      :on-change on-change
                      :on-click #(reset! support-level :user-defined)}]) " per month"]]
      (when-not @confirming-cancel?
        [:div.field
         (when-not @need-card?
           [:button {:class (str "ui button primary "
                                 (when @loading?
                                   " disabled"))}
            "Continue"])
         (when @need-card?
           [:div {:class "ui button primary"
                  :on-click (fn [e]
                              (.preventDefault e)
                              (dispatch [:payment/set-calling-route! (str "/p/" @(subscribe [:active-project-id]) "/support")])
                              (dispatch [:navigate [:payment]]))}
            "Update Payment Information"])
         (when-not (nil? @current-support-level)
           [:button {:class (str "ui button "
                                 (when @loading?
                                   " disabled"))
                     :on-click (fn [e]
                                 (reset! error-message nil)
                                 (.preventDefault e)
                                 (reset! confirming-cancel? true))}
            "Cancel Support"])])
      (when @confirming-cancel?
        [:div
         [:h3.ui.red.header
          "Are you sure want to end your support for this project?"]
         [:div.field
          [:button {:class (str "ui button green"
                                (when @loading?
                                  " disabled"))
                    :on-click (fn [e]
                                (.preventDefault e)
                                (reset! confirming-cancel? false))}
           "Continue to Support"]
          [:button {:class (str "ui button red"
                                (when @loading?
                                  " disabled"))
                    :on-click (fn [e]
                                (.preventDefault e)
                                (reset! loading? true)
                                (dispatch [:action [:support/cancel]]))}
           "Stop Support"]]])
      (when @error-message
        [:div.ui.red.header @error-message])]]))

(defn SupportFormOnce
  [state]
  (let [support-level (r/cursor state [:support-level "once"])
        user-support-level (r/cursor state [:user-support-level])
        error-message (r/cursor state [:error-message "once"])
        need-card? (r/cursor stripe/state [:need-card?])
        loading? (r/cursor state [:loading?])
        ;; current-support-level (r/cursor state [:current-support-level])
        confirming-cancel? (r/cursor state [:confirming-cancel?])
        frequency "once"]
    (r/create-class {:reagent-render
                     (fn [this]
                       [:div.ui.segment
                        [:h1 "Make a One-Time Contribution"]
                        #_(when @current-support-level
                            [:h3.support-message (str "You are currently supporting this project at " (accounting/cents->string @current-support-level) " per month")])
                        [Form {:on-submit
                               (fn []
                                 (let [cents (accounting/string->cents @user-support-level)]
                                   (cond (and (= @support-level
                                                 :user-defined)
                                              (= cents
                                                 0))
                                         (reset! user-support-level "$0.00")
                                         (and (= @support-level
                                                 :user-defined)
                                              (> cents
                                                 0))
                                         (do
                                           (reset! loading? true)
                                           (dispatch [:action [:support/support-plan cents frequency]]))
                                         :else
                                         (do
                                           (reset! loading? true)
                                           (dispatch [:action [:support/support-plan @support-level frequency]])))))}
                         [FormGroup
                          [FormRadio {:label "$5"
                                      :checked (= @support-level
                                                  500)
                                      :on-change #(reset! support-level 500)}]]
                         [FormGroup
                          [FormRadio {:label "$10"
                                      :checked (= @support-level
                                                  1000)
                                      :on-change #(reset! support-level 1000)}]]
                         [FormGroup
                          [FormRadio {:label "$50"
                                      :checked (= @support-level
                                                  5000)
                                      :on-change #(reset! support-level 5000)}]]
                         [FormGroup
                          [FormRadio {:checked (= @support-level
                                                  :user-defined)
                                      :on-change #(reset! support-level :user-defined)}]
                          [:div
                           (let [on-change (fn [event]
                                             (let [value ($ event :target.value)
                                                   dollar-sign-on-front? (fn [value]
                                                                           (= ($ value indexOf "$")
                                                                              0))
                                                   new-value (cond
                                                               (not (dollar-sign-on-front? value))
                                                               (str "$" ($ event :target.value))
                                                               :else
                                                               value)]
                                               (reset! user-support-level new-value)))]
                             [FormInput {:value @user-support-level
                                         :on-change on-change
                                         :on-click #(reset! support-level :user-defined)}])]]
                         (when-not @confirming-cancel?
                           [:div.field
                            (when-not @need-card?
                              [:button {:class (str "ui button primary "
                                                    (when @loading?
                                                      " disabled"))}
                               "Continue"])
                            (when @need-card?
                              [:div {:class "ui button primary"
                                     :on-click (fn [e]
                                                 (.preventDefault e)
                                                 (dispatch [:payment/set-calling-route! (str "/p/" @(subscribe [:active-project-id]) "/support")])
                                                 (dispatch [:navigate [:payment]]))}
                               "Update Payment Information"])
                            #_(when-not (nil? @current-support-level)
                                [:button {:class (str "ui button "
                                                      (when @loading?
                                                        " disabled"))
                                          :on-click (fn [e]
                                                      (reset! error-message nil)
                                                      (.preventDefault e)
                                                      (reset! confirming-cancel? true))}
                                 "Cancel Support"])])
                         #_(when @confirming-cancel?
                             [:div
                              [:h3.ui.red.header
                               "Are you sure want to end your support for this project?"]
                              [:div.field
                               [:button {:class (str "ui button green"
                                                     (when @loading?
                                                       " disabled"))
                                         :on-click (fn [e]
                                                     (.preventDefault e)
                                                     (reset! confirming-cancel? false))}
                                "Continue to Support"]
                               [:button {:class (str "ui button red"
                                                     (when @loading?
                                                       " disabled"))
                                         :on-click (fn [e]
                                                     (.preventDefault e)
                                                     (reset! loading? true)
                                                     (dispatch [:action [:support/cancel]]))}
                                "Stop Support"]]])
                         (when @error-message
                           [:div.ui.red.header @error-message])]])
                     :component-did-mount
                     (fn [this]
                       (reset! support-level 500))})))

(defn Support
  []
  (let [need-card? (r/cursor stripe/state [:need-card?])]
    (get-current-support)
    [:div.panel
     [SupportFormMonthly state]]))

(defn UserSupportSubscriptions
  []
  (let [user-support-subscriptions (r/cursor state [:user-support-subscriptions])]
    (get-user-support-subscriptions)
    (when-not (empty? @user-support-subscriptions)
      [:div.ui.segment
       [:h3 "Thank You For Supporting These Projects"]
       [:table.ui.striped.table
        [:thead
         [:tr [:th "Project"] [:th "Amount"] [:th]]]
        [:tbody
         (doall (map
                 (fn [subscription]
                   ^{:key (:project-id subscription)}
                   [:tr
                    [:td (:name subscription)]
                    [:td (accounting/cents->string (:quantity subscription))]
                    [:td [:a {:href (str "/p/" (:project-id subscription) "/support")}
                          "Change Your Level of Support"]]])
                 @user-support-subscriptions))]]])))

(defmethod panel-content panel []
  (fn [child]
    (when (nil? @(r/cursor state [:user-support-level-monthly]))
      (reset! (r/cursor state [:user-support-level-monthly]) "$1.00"))
    (reset! (r/cursor state [:error-message "monthly"]) "")
    (reset! (r/cursor state [:error-message "once"]) "")
    (reset! (r/cursor state [:confirm-message]) "")
    (reset! (r/cursor state [:loading?]) false)
    (reset! (r/cursor state [:confirming-cancel?]) false)
    [Support]))