(ns sysrev.views.panels.pricing
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [sysrev.nav :refer [nav make-url]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer
             [Segment Column Row Grid Icon Button Popup Divider ListUI ListItem ListContent]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [sr-defroute setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:pricing])

(defn PricingItem [{:keys [icon icon-color content]
                    :or {icon "check", icon-color "green"}}]
  [ListItem
   [Icon {:name icon :color icon-color}]
   [ListContent content]])

(defn PublicProjects []
  [:div "Unlimited "
   [Popup {:trigger (r/as-element [:a {:href "https://github.com/sysrev/Sysrev_Documentation/wiki/FAQ#what-is-the-difference-between-a-public-and-private-project"}
                                   "public projects"])
           :content "Public project content can be viewed by anyone"}]])

(defn PrivateProjects []
  [:div "Unlimited "
   [Popup {:trigger (r/as-element [:a {:href "https://github.com/sysrev/Sysrev_Documentation/wiki/FAQ#what-is-the-difference-between-a-public-and-private-project"}
                                   "private projects"])
           :content "Private project content can only be viewed by project members "}]])

(defn Pricing []
  (let [logged-in? (subscribe [:self/logged-in?])
        current-plan (:name @(subscribe [:user/current-plan]))]
    (when (and @logged-in? (nil? current-plan))
      (dispatch [:data/load [:user/current-plan @(subscribe [:self/user-id])]]))
    [:div
     [:h3 {:id "pricing-header"} "Pricing"]
     [Grid (if (util/mobile?)
             {:columns 1}
             {:columns "equal" :id "pricing-plans"})
      [Row
       [Column [Segment (when (util/mobile?)
                          {:style {:margin-bottom "1em"}})
                [:div {:class "pricing-list-header"}
                 [:h3 "Free"]
                 [:h2 "$0"]
                 [:h4 "Per month"]
                 [:p "The basics of Sysrev for every researcher"]]
                [ListUI
                 [PricingItem {:content [PublicProjects]}]
                 [PricingItem {:content "Unlimited project reviewers"}]
                 [PricingItem {:content "Project management"}]
                 [PricingItem {:content "Free lifetime storage for public projects"
                               :icon "cloud"}]]
                [Button {:on-click #(nav "/register")
                         :primary true
                         :disabled @logged-in?
                         :fluid true}
                 (if @logged-in? "Already signed up!" "Choose Free")]]]
       [Column [Segment (when (util/mobile?)
                          {:style {:margin-bottom "1em"}})
                [:div {:class "pricing-list-header"}
                 [:h3 "Pro"]
                 [:h2 "$10"]
                 [:h4 "Per month"]
                 [:p "Pro tools for researchers with advanced requirements"]]
                [ListUI
                 [PricingItem {:icon "check" :content [PublicProjects]}]
                 [PricingItem {:icon "check" :content [PrivateProjects]}]
                 [PricingItem {:content "Unlimited project reviewers"}]
                 [PricingItem {:icon "check" :content "Project management"}]
                 [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
                 [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]]
                [Button {:on-click (fn [_e]
                                     (if @logged-in?
                                       (nav "/user/plans")
                                       (nav "/register" :params {:redirect "/user/plans"})))
                         :primary true
                         :fluid true
                         :disabled (= current-plan "Unlimited_User")}
                 (if (= current-plan "Unlimited_User")
                   "Already signed up!"
                   "Choose Pro")]]]
       [Column [Segment (when (util/mobile?)
                          {:style {:margin-bottom "1em"}})
                [:div {:class "pricing-list-header"}
                 [:h3 "Team Pro"]
                 [:h2 "$10"]
                 [:h4 "Per member / month"]
                 [:p "Advanced collaboration and management tools for teams"]]
                [ListUI
                 [PricingItem {:icon "check" :content [PublicProjects]}]
                 [PricingItem {:icon "check" :content [PrivateProjects]}]
                 [PricingItem {:content "Unlimited project reviewers"}]
                 [PricingItem {:icon "check" :content "Project management"}]
                 [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
                 [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]
                 [PricingItem {:icon "users" :content "Group adminstration tools"}]]
                [:p {:class "team-pricing"}
                 "Starts at " [:b "$30 / month"] " and includes your first 5 team members"]
                [Button {:on-click #(if @logged-in?
                                      (nav (make-url "/create/org" {:type "existing-account"}))
                                      (nav "/register"
                                           :params {:redirect (make-url "/create/org"
                                                                        {:type "new-account"})}))
                         :primary true
                         :fluid true}
                 "Choose Team Pro"]]]
       [Column [Segment (when (util/mobile?)
                          {:style {:margin-bottom "1em"}})
                [:div {:class "pricing-list-header"}
                 [:h3 "Enterprise"]
                 [:h3 [:a {:href "mailto:sales@sysrev.com"}
                       "Contact Sales for pricing"]]
                 [:p {:class "customized-plans"}
                  "Customized plans tailored to your organization's needs"]]
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
                 [PricingItem {:content "Contracted expert reviewers"}]]
                [:p {:class "team-pricing"}
                 "Contact " [:a {:href "mailto:sales@sysrev.com"} "us"]
                 " about designing a custom data processing and analysis solution to meet your needs today!"]]]]]]))

(defmethod panel-content panel []
  (fn [_child] [Pricing]))

(sr-defroute pricing "/pricing" []
             (dispatch [:set-active-panel panel]))
