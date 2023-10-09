(ns sysrev.views.panels.pricing
  (:require [cljs-time.core :as time]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]
            [sysrev.nav :refer [make-url]]
            [sysrev.shared.plans-info :as plans-info]
            [sysrev.util :as util :refer [css]]
            [sysrev.views.semantic :refer [Button Column Divider Grid Header
                                           Icon ListContent ListItem ListUI
                                           Popup Row Segment]]))

(declare panel)
(setup-panel-state panel [:pricing])

(defn- PricingItem [{:keys [icon icon-color content]
                     :or {icon "check" icon-color "green"}}]
  [ListItem
   [Icon {:name icon :color icon-color :style {:line-height "1.142em"}}]
   [ListContent content]])

(defn- PublicProjects []
  [:div "Unlimited "
   [Popup {:trigger (r/as-element [:a {:href "https://github.com/sysrev/Sysrev_Documentation/wiki/FAQ#what-is-the-difference-between-a-public-and-private-project"}
                                   "public projects"])
           :content "Public project content can be viewed by anyone"}]])

(defn- PrivateProjects []
  [:div "Unlimited "
   [Popup {:trigger (r/as-element [:a {:href "https://github.com/sysrev/Sysrev_Documentation/wiki/FAQ#what-is-the-difference-between-a-public-and-private-project"}
                                   "private projects"])
           :content "Private project content can only be viewed by project members"}]])

(defn FreeBenefits []
  [ListUI
   [PricingItem {:content [PublicProjects]}]
   [PricingItem {:content "Unlimited project reviewers"}]
   [PricingItem {:content "Project management"}]
   [PricingItem {:content "Free lifetime storage for public projects"
                 :icon "cloud"}]])

(defn TeamProBenefits []
  [ListUI
   [PricingItem {:icon "check" :content [PublicProjects]}]
   [PricingItem {:icon "check" :content [PrivateProjects]}]
   [PricingItem {:content "Unlimited project reviewers"}]
   [PricingItem {:icon "check" :content "Project management"}]
   [PricingItem {:icon "check"
                 :content [:span "Sysrev Analytics - "
                           [:a {:href "https://youtu.be/FgxJ4zTVUn4"
                                :target "_blank"}
                            "analytics demo"]]}]
   [PricingItem {:icon "tags" :content [:span "Group Labels - "
                                        [:a {:href "https://youtu.be/3FzJIXGOXq0"
                                             :target "_blank"}
                                         "group labels demo"]]}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]
   [PricingItem {:icon "users" :content "Group adminstration tools"}]])

(defn- EnterpriseBenefits []
  [ListUI
   [PricingItem {:content "Everything included in Premium"}]
   [Divider]
   [PricingItem {:content "Self-hosted or cloud-hosted"}]
   [PricingItem {:content "Priority support"}]
   [PricingItem {:content "Access provisioning"}]
   [PricingItem {:content "Invoice billing"}]
   [PricingItem {:content "Unique data sources"}]
   [PricingItem {:content "AI models tailored to your organization's needs"}]
   [PricingItem {:content "Customized feature development"}]
   [PricingItem {:content "Contracted expert reviewers"}]])

(defn- PricingSegment [& {:keys [class title price per-item intro benefits content]}]
  [Column {:class (css "pricing-segment" class)}
   [Segment
    [:div.pricing-list-header
     [:h3.title title]
     (when price [:h2.price price])
     (when per-item [:h4.per-item per-item])
     (when intro [:p.intro intro])]
    (when benefits benefits)
    (doall content)]])

(defn Pricing []
  (let [logged-in? (subscribe [:self/logged-in?])
        user-id (subscribe [:self/user-id])]
    (when (and @logged-in? (nil? (:nickname @(subscribe [:user/current-plan]))))
      (dispatch [:data/load [:user/current-plan @user-id]])
      (dispatch [:fetch [:user/orgs @user-id]]))
    (fn []
      (let [current-plan (:nickname @(subscribe [:user/current-plan]))]
        [:div
         [Header {:id "pricing-header" :as "h2" :align "center"} "Pricing"]
         (when (< (time/now) (time/date-time 2020 9 1))
           [Header {:as "h3" :align "center"
                    :style {:margin-top "0px" :margin-bottom "0.5em"}}
            "Sign up for Premium before August 31" [:sup "th"]
            " 2020 and " [:a {:href "/promotion"} " apply here"]
            " to be eligible for a $500 project award."])
         [Grid {:columns "equal" :id "pricing-plans" :stackable true}
          [Row
           (PricingSegment
            :class "pricing-free"
            :title "Basic" :price "$0" :per-item "Per month"
            :intro "The basics of Sysrev for every researcher"
            :benefits [FreeBenefits]
            :content
            (list [Button {:key :button
                           :href (if @logged-in?
                                   (make-url "/create/org" {:plan "basic"})
                                   "/register")
                           :fluid true :primary true}
                   (if @logged-in? "Create a Free Team" "Choose Free")]))
           (PricingSegment
            :class "pricing-team-pro"
            :title "Premium" :price "$50" :per-item "Per member / month"
            :intro "Advanced collaboration and management tools for teams"
            :benefits [TeamProBenefits]
            :content
            (list
             [Button {:key :button
                      :href (if @logged-in?
                              "/user/plans"
                              (make-url "/register"
                                        {:redirect "/user/plans"
                                         :redirect_message
                                         "Create a free account to upgrade to Premium Plan"}))
                      :fluid true :primary true
                      :disabled (plans-info/pro? current-plan)}
              (if (plans-info/pro? current-plan)
                "Already signed up!" "Choose Premium")]))
           (PricingSegment
            :class "pricing-enterprise"
            :title "Enterprise" :price "$1000" :per-item "Per month"
            :intro "Customized plans tailored to your organization's needs"
            :benefits [EnterpriseBenefits]
            :content
            (list
             [Button {:key :button
                      :href (if @logged-in?
                              "https://buy.stripe.com/4gwg0P96T5674Mg7st"
                              (make-url "/register"
                                        {:redirect "/user/plans"
                                         :redirect_message
                                         "Create a free account to upgrade to Enterprise Plan"}))
                      :fluid true :primary true}
              "Choose Enterprise"]))]]]))))

(def-panel :uri "/pricing" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content [Pricing])
