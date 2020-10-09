(ns sysrev.views.panels.pricing
  (:require [cljs-time.core :as time]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [sysrev.nav :refer [nav make-url]]
            [sysrev.stripe :as stripe]
            [sysrev.views.semantic :refer [Segment Column Row Grid Icon Button Popup Divider
                                           ListUI ListItem ListContent Dropdown Header]]
            [sysrev.util :as util :refer [when-test css]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

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
           :content "Private project content can only be viewed by project members "}]])

(defn FreeBenefits []
  [ListUI
   [PricingItem {:content [PublicProjects]}]
   [PricingItem {:content "Unlimited project reviewers"}]
   [PricingItem {:content "Project management"}]
   [PricingItem {:content "Free lifetime storage for public projects"
                 :icon "cloud"}]])

(defn ProBenefits []
  [ListUI
   [PricingItem {:icon "check" :content [PublicProjects]}]
   [PricingItem {:icon "check" :content [PrivateProjects]}]
   [PricingItem {:content "Unlimited project reviewers"}]
   [PricingItem {:icon "check" :content "Project management"}]
   [PricingItem {:icon "check"
                 :content [:span "Sysrev Analytics - "
                           [:a {:href "https://blog.syssrev.com/analytics"}
                            "analytics blog"]]}]
   [PricingItem {:icon "tags" :content "Group Labels"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]])

(defn TeamProBenefits []
  [ListUI
   [PricingItem {:icon "check" :content [PublicProjects]}]
   [PricingItem {:icon "check" :content [PrivateProjects]}]
   [PricingItem {:content "Unlimited project reviewers"}]
   [PricingItem {:icon "check" :content "Project management"}]
   [PricingItem {:icon "check"
                 :content [:span "Sysrev Analytics - "
                           [:a {:href "https://blog.syssrev.com/analytics"}
                            "analytics blog"]]}]
   [PricingItem {:icon "tags" :content "Group Labels"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]
   [PricingItem {:icon "users" :content "Group adminstration tools"}]])

(defn- EnterpriseBenefits []
  [ListUI
   [PricingItem {:content "Everything included in Team Pro"}]
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
        user-id (subscribe [:self/user-id])
        selected-org (r/atom nil)]
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
            "Sign up for Team Pro before August 31" [:sup "th"]
            " 2020 and " [:a {:href "/promotion"} " apply here"]
            " to be eligible for a $500 project award."])
         [Grid {:columns "equal" :id "pricing-plans" :stackable true}
          [Row
           (PricingSegment
            :class "pricing-free"
            :title "Free" :price "$0" :per-item "Per Month"
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
            :class "pricing-pro"
            :title "Pro" :price "$10" :per-item "Per month"
            :intro "Pro tools for researchers with advanced requirements"
            :benefits [ProBenefits]
            :content
            (list [Button {:key :button
                           :href (if @logged-in?
                                   "/user/plans"
                                   (make-url "/register"
                                             {:redirect "/user/plans"
                                              :redirect_message
                                              "Create a free account to upgrade to Pro Plan"}))
                           :fluid true :primary true
                           :disabled (stripe/user-pro? current-plan)}
                   (if (stripe/user-pro? current-plan)
                     "Already signed up!" "Choose Pro")]))
           (PricingSegment
            :class "pricing-team-pro"
            :title "Team Pro" :price "$10" :per-item "Per member / month"
            :intro "Advanced collaboration and management tools for teams"
            :benefits [TeamProBenefits]
            :content
            (list
             [:p.team-pricing {:key :team-pricing}
              "Starts at " [:b "$30 / month"] " and includes your first 5 team members"]
             (if @logged-in?
               [Dropdown {:key :button
                          :value @selected-org
                          :options (->> @(subscribe [:user/orgs @user-id])
                                        ;; get the orgs you have permission to modify
                                        (filter #(some #{"owner" "admin"} (:permissions %)))
                                        ;; and the orgs who aren't pro plans
                                        (remove #(stripe/pro? (-> % :plan :nickname)))
                                        (map (fn [{:keys [group-name group-id]}]
                                               {:text group-name :value group-id}))
                                        (cons {:text "+ Create Organization"
                                               :value "create-org"}))
                          :on-change
                          (fn [_e data]
                            (if-let [value (when-test int? (.-value data))]
                              (nav (str "/org/" value "/plans"))
                              (nav "/create/org" :params {:panel-type "existing-account"
                                                          :plan "pro"})))
                          :text "Continue with Team Pro"
                          :fluid true :button true :select-on-blur false
                          :class "primary"}]
               [Button {:key :button
                        :href (make-url
                               "/register"
                               {:redirect (make-url "/create/org" {:panel-type "new-account"
                                                                   :plan "pro"})
                                :redirect_message
                                "Create a free account before moving on to team creation"})
                        :fluid true :primary true} "Choose Team Pro"])))
           (PricingSegment
            :class "pricing-enterprise"
            :title "Enterprise" :price [:a {:href "mailto:sales@sysrev.com"}
                                        "Contact Sales for pricing"]
            :intro "Customized plans tailored to your organization's needs"
            :benefits [EnterpriseBenefits]
            :content
            (list
             [:p.team-pricing {:key :team-pricing}
              [:a {:href "mailto:sales@sysrev.com"} "Contact us"]
              " about designing a custom data processing and analysis solution to meet your needs today!"]))]]]))))

(def-panel :uri "/pricing" :panel panel
  :on-route (dispatch [:set-active-panel panel])
  :content [Pricing])
