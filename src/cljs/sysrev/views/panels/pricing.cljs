(ns sysrev.views.panels.pricing
  (:require [cljs-time.core :as time]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [sysrev.nav :refer [nav make-url]]
            [sysrev.stripe :as stripe]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer
             [Segment Column Row Grid Icon Button Popup Divider ListUI ListItem ListContent Dropdown]]

            [sysrev.util :as util]
            [sysrev.macros :refer-macros [sr-defroute setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:pricing])

(defn PricingItem [{:keys [icon icon-color content]
                    :or {icon "check", icon-color "green"}}]
  [ListItem
   [Icon {:name icon :color icon-color :style {:line-height "1.142em"}}]
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
   [PricingItem {:icon "check" :content [:span "Sysrev Analytics - " [:a {:href "https://blog.syssrev.com/analytics"} "analytics blog"]]}]
   [PricingItem {:icon "tags" :content "Group Labels"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]])

(defn TeamProBenefits []
  [ListUI
   [PricingItem {:icon "check" :content [PublicProjects]}]
   [PricingItem {:icon "check" :content [PrivateProjects]}]
   [PricingItem {:content "Unlimited project reviewers"}]
   [PricingItem {:icon "check" :content "Project management"}]
   [PricingItem {:icon "check" :content [:span "Sysrev Analytics - " [:a {:href "https://blog.syssrev.com/analytics"} "analytics blog"]]}]
   [PricingItem {:icon "tags" :content "Group Labels"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for public projects"}]
   [PricingItem {:icon "cloud" :content "Free lifetime storage for private projects"}]
   [PricingItem {:icon "users" :content "Group adminstration tools"}]])

(defn Pricing []
  (let [logged-in? (subscribe [:self/logged-in?])
        user-id (subscribe [:self/user-id])
        current-plan (:nickname @(subscribe [:user/current-plan]))
        selected-org (r/atom nil)
        user-orgs (subscribe [:user/orgs @user-id])
        org-options (fn [orgs]
                      (->> orgs
                           ;; get the orgs you have permission to modify
                           (filter #(some #{"owner" "admin"} (:permissions %)))
                           ;; and the orgs who aren't pro plans
                           (filter #(not (contains? stripe/pro-plans (-> % :plan :nickname))))
                           (map #(hash-map :text (:group-name %)
                                           :value (:group-id %)))
                           (cons {:text "+ Create Organization"
                                  :value "create-org"})))]
    (fn []
      (when (and @logged-in? (nil? current-plan))
        (dispatch [:data/load [:user/current-plan @(subscribe [:self/user-id])]])
        (dispatch [:fetch [:user/orgs @user-id]]))
      [:div
       [:div {:style {:text-align "center"}}
        [:h2 {:id "pricing-header"} "Pricing"]
        (when (< (time/now) (time/date-time 2020 9 1))
          [:h3 {:style {:margin-top "0px" :margin-bottom "10px"}}
           "Sign up for Team Pro before August 31" [:sup "th"]
           " 2020 and " [:a {:href "/promotion"} " apply here"] " to be eligible for a  $500 project award."])]
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
                  [FreeBenefits]
                  [Button {:on-click (if @logged-in?
                                       #(nav "/create/org" :params {:plan "basic"})
                                       #(nav "/register"))
                           :primary true
                           :fluid true}
                   (if @logged-in? "Create a Free Team" "Choose Free")]]]
         [Column [Segment (when (util/mobile?)
                            {:style {:margin-bottom "1em"}})
                  [:div {:class "pricing-list-header"}
                   [:h3 "Pro"]
                   [:h2 "$10"]
                   [:h4 "Per month"]
                   [:p "Pro tools for researchers with advanced requirements"]]
                  [ProBenefits]
                  [Button {:on-click (fn [_e]
                                       (if @logged-in?
                                         (nav "/user/plans")
                                         (nav "/register" :params {:redirect "/user/plans"
                                                                   :redirect_message "Create a free account to upgrade to Pro Plan"})))
                           :primary true
                           :fluid true
                           :disabled (contains? #{"Unlimited_User"
                                                  "Unlimited_User_Annual"} current-plan)}
                   (if (contains? #{"Unlimited_User"
                                    "Unlimited_User_Annual"} current-plan)
                     "Already signed up!"
                     "Choose Pro")]]]
         [Column [Segment (when (util/mobile?)
                            {:style {:margin-bottom "1em"}})
                  [:div {:class "pricing-list-header"}
                   [:h3 "Team Pro"]
                   [:h2 "$10"]
                   [:h4 "Per member / month"]
                   [:p "Advanced collaboration and management tools for teams"]]
                  [TeamProBenefits]
                  [:p {:class "team-pricing"}
                   "Starts at " [:b "$30 / month"] " and includes your first 5 team members"]
                  (if @logged-in?
                    [Dropdown {:value @selected-org
                               :options (org-options @user-orgs)
                               :select-on-blur false
                               :on-change (fn [_event data]
                                            (let [value (.-value data)]
                                              (if (int? value)
                                                (nav (str "/org/" value "/plans"))
                                                (nav "/create/org"
                                                     :params {:panel-type "existing-account"
                                                              :plan "pro"}))))
                               :text "Continue with Team Pro"
                               :button true
                               :fluid true
                               :class-name "fluid primary button"}]
                    [Button {:on-click #(nav "/register"
                                             :params {:redirect (make-url "/create/org"
                                                                          {:panel-type "new-account"
                                                                           :plan "pro"})
                                                      :redirect_message "Create a free account before moving on to team creation"})
                             :primary true
                             :fluid true} "Choose Team Pro"])]]
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
                   " about designing a custom data processing and analysis solution to meet your needs today!"]]]]]])))

(defmethod panel-content panel []
  (fn [_child] [Pricing]))

(sr-defroute pricing "/pricing" []
             (dispatch [:set-active-panel panel]))
