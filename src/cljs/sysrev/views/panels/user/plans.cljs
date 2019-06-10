(ns sysrev.views.panels.user.plans
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe reg-event-db trim-v reg-sub reg-event-fx]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.identity :refer [current-user-id]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :as s :refer
             [Segment Grid Column Row ListUI ListItem Button]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.user.billing :refer [DefaultSource]]
            [sysrev.nav :refer [nav nav-scroll-top]]
            [sysrev.util :as util])
  (:require-macros [reagent.interop :refer [$]]
                   [sysrev.macros :refer [with-loader setup-panel-state]]))

(setup-panel-state {:path [:plans]
                    :panel-var panel
                    :state-var state
                    :get-fn panel-get
                    :set-fn panel-set
                    :get-sub ::get
                    :set-event ::set})

(def-data :user/current-plan
  :loaded? (fn [db user-id] (-> (get-in db [:data :plans])
                                (contains? :current-plan)))
  :uri (fn [user-id] (str "/api/user/" user-id "/stripe/current-plan"))
  :process (fn [{:keys [db]} _ {:keys [plan] :as result}]
             {:db (assoc-in db [:data :plans :current-plan] plan)}))

(reg-sub :user/current-plan #(get-in % [:data :plans :current-plan]))

(reg-sub :user/on-subscribe-nav-to-url #(panel-get % :on-subscribe-nav-to-url))

(reg-event-db :user/set-on-subscribe-nav-to-url!
              (fn [db [_ url]] (panel-set db :on-subscribe-nav-to-url url)))

(def-action :subscribe-plan
  :uri (fn [] (str "/api/user/" @(subscribe [:self/user-id]) "/stripe/subscribe-plan"))
  :content (fn [plan-name] {:plan-name plan-name})
  :process (fn [{:keys [db]} _ result]
             (when (:created result)
               {:db (-> (panel-set db :changing-plan? false)
                        (panel-set :error-message nil))
                :dispatch-n (list
                             ;; need to download all projects associated with the user
                             ;; to update [:project/subscription-lapsed?] for MakePublic
                             [:project/fetch-all-projects]
                             [:fetch [:user/current-plan (current-user-id db)]])
                :nav-scroll-top @(subscribe [:user/on-subscribe-nav-to-url])}))
  :on-error (fn [{:keys [db error]} _ _]
              (let [msg (if (= (:type error) "invalid_request_error")
                          "You must enter a valid payment method before subscribing to this plan"
                          (:message error))]
                {:db (-> (panel-set db :error-message msg)
                         (stripe/panel-set :need-card? true))})))

(defn cents->dollars
  "Converts an integer value of cents to dollars"
  [cents]
  (str (-> cents (/ 100) (.toFixed 2))))

(defn price-summary
  [{:keys [tiers member-count]}]
  (let [base (->> tiers (map :flat_amount) (filter int?) (apply +))
        per-user (->> tiers (map :unit_amount) (filter int?) (apply +))
        up-to (->> tiers (map :up_to) (filter int?) first)
        monthly-bill (+ base (* (max 0 (- member-count 5)) per-user))]
    {:base base
     :per-user per-user
     :up-to up-to
     :monthly-bill monthly-bill}))

(defn Unlimited
  [{:keys [unlimited-plan-price
           unlimited-plan-name]}]
  [Segment
   [Grid {:stackable true}
    [Row
     [Column {:width 6}
      [:b unlimited-plan-name]
      [ListUI
       [ListItem "Unlimited public projects"]
       [ListItem "Unlimited private projects"]]]
     [Column {:width 10 :align "right"}
      (if (map? unlimited-plan-price)
        (let [{:keys [base per-user up-to monthly-bill]} (price-summary unlimited-plan-price)]
          [:div
           [Row [:h3 "$" (cents->dollars base) " / month"]]
           [Row [:h3 "up to 5 org members"]]
           [:br]
           [Row [:h3 "$ " (cents->dollars per-user) " / month"]]
           [Row [:h3 "per additional member"]]])
        [Row [:h3 (str "$" (cents->dollars unlimited-plan-price) " / month")] ])]]]])

(defn BasicPlan []
  [Segment
   [Grid {:stackable true}
    [Row
     [Column {:width 8}
      [:b "Basic"]
      [ListUI
       [ListItem "Unlimited public projects"]]]
     [Column {:width 8 :align "right"}
      [:h2 "$0 / month"]]]]])

(defn TogglePlanButton
  [{:keys [disabled on-click class]} & [text]]
  [Button
   {:class class
    :color "green"
    :on-click
    on-click
    :disabled disabled}
   text])

(defn DowngradePlan [{:keys [billing-settings-uri
                             on-downgrade
                             unlimited-plan-price
                             unlimited-plan-name]}]
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:h1 "Unsubscribe from your plan"]
         [Grid
          [Row
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe from"]
                        [Unlimited {:unlimited-plan-price unlimited-plan-price
                                    :unlimited-plan-name unlimited-plan-name}]]]]
            [Grid [Row [Column
                        [:h3 "New Plan"]
                        [BasicPlan]
                        [:a {:href billing-settings-uri} "Billing Settings"]]]]]
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "Unsubscribe Summary"]
                        [ListUI {:divided true}
                         [:h4 "New Monthly Bill"]
                         [ListItem [:p "Basic plan ($0 / month)"]]
                         [:div {:style {:margin-top "1em" :width "100%"}}
                          [TogglePlanButton {:disabled (or @changing-plan?)
                                             :on-click #(do (reset! changing-plan? true)
                                                            (on-downgrade))
                                             :class "unsubscribe-plan"} "Unsubscribe"]]
                         (when @error-message
                           [s/Message {:negative true}
                            [s/MessageHeader "Change Plan Error"]
                            [:p @error-message]])]]]]]]]])
      :get-initial-state
      (fn [this]
        (reset! changing-plan? false)
        (reset! error-message nil))})))

(defn UpgradePlan [{:keys [billing-settings-uri
                           on-upgrade
                           default-source-atom
                           get-default-source
                           on-add-payment-method
                           unlimited-plan-price
                           unlimited-plan-name]}]
  (let [error-message (r/cursor state [:error-message])
        changing-plan? (r/cursor state [:changing-plan?])]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:h1 "Upgrade your plan"]
         [Grid
          [Row
           [Column {:width 8}
            [Grid [Row [Column
                        [:h3 "UPGRADING TO"]
                        [Unlimited {:unlimited-plan-price unlimited-plan-price
                                    :unlimited-plan-name unlimited-plan-name}]
                        [:a {:href billing-settings-uri} "Billing Settings"]]]]]
           [Column {:width 8}
            (let [no-default? (empty? @default-source-atom)]
              [Grid
               [Row
                [Column
                 [:h3 "Upgrade Summary"]
                 [ListUI {:divided true}
                  [:h4 "New Monthly Bill"]
                  [ListItem [:p (str "Unlimited plan ("
                                     (if (map? unlimited-plan-price)
                                       (str "$" (-> unlimited-plan-price
                                                    price-summary
                                                    :monthly-bill
                                                    cents->dollars)
                                            " / month")
                                       (str "$" (cents->dollars unlimited-plan-price) " / month"))
                                     ")")]]
                  [:h4 "Billing Information"]
                  [ListItem [DefaultSource {:get-default-source get-default-source
                                            :default-source-atom default-source-atom}]]
                  (when (empty? @error-message)
                    [:a.payment-method
                     {:class (if no-default? "add-method" "change-method")
                      :style {:cursor "pointer"}
                      :on-click (util/wrap-prevent-default
                                 #(do (reset! error-message nil)
                                      (on-add-payment-method)))}
                     (if no-default?
                       "Add a payment method"
                       "Change payment method")])
                  [:div {:style {:margin-top "1em" :width "100%"}}
                   [TogglePlanButton {:disabled (or no-default? @changing-plan?)
                                      :on-click #(do (reset! changing-plan? true)
                                                     (on-upgrade))
                                      :class "upgrade-plan"}
                    "Upgrade Plan"]]
                  (when @error-message
                    [s/Message {:negative true}
                     [s/MessageHeader "Change Plan Error"]
                     [:p @error-message]])]]]])]]]])
      :get-initial-state
      (fn [this]
        (reset! changing-plan? false)
        (reset! error-message nil))})))

(defmethod logged-out-content [:plans] []
  (logged-out-content :logged-out))

(defn UserPlans []
  (let [self-id (subscribe [:self/user-id])]
    ;; this seems to work as a replacement for :component-did-mount
    (when @self-id (dispatch [:reload [:user/current-plan @self-id]]))
    (fn []
      (when @self-id
        (with-loader [[:user/current-plan @self-id]] {}
          (let [current-plan @(subscribe [:user/current-plan])]
            (condp = (:name current-plan)
              "Basic"
              [UpgradePlan {:billing-settings-uri (str "/user/" @self-id "/billing")
                            :default-source-atom (subscribe [:stripe/default-source "user" @self-id])
                            :get-default-source stripe/get-user-default-source
                            :on-upgrade (fn [] (dispatch [:action [:subscribe-plan "Unlimited_User"]]))
                            :on-add-payment-method
                            #(do (dispatch [:payment/set-calling-route! "/user/plans"])
                                 (dispatch [:navigate [:payment]]))
                            :unlimited-plan-price 1000
                            :unlimited-plan-name "Pro Plan"}]
              "Unlimited_User"
              [DowngradePlan {:billing-settings-uri (str "/user/" @self-id "/billing")
                              :on-downgrade (fn [] (dispatch [:action [:subscribe-plan "Basic"]]))
                              :unlimited-plan-name "Pro Plan"
                              :unlimited-plan-price 1000}]
              [s/Message {:negative true}
               [s/MessageHeader "User Plans Error"]
               [:div.content
                [:p (str "Plan (" (:name current-plan) ") is not recognized for self-id: " @self-id)]
                [:p (str "Active route: " @active-route)]]])))))))

(defmethod panel-content [:plans] []
  (fn [child]
    [UserPlans]))
