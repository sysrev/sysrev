(ns sysrev.views.panels.root
  (:require [cljs-time.core :as time]
            [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.pricing :refer [Pricing]]
            [sysrev.views.project-list :as plist]
            [sysrev.nav :refer [nav]]
            [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:root])

(reg-sub :landing-page?
         :<- [:active-panel]
         :<- [:self/logged-in?]
         (fn [[active-panel logged-in?]]
           (and (not logged-in?)
                (or (= active-panel [:lit-review])
                    (= active-panel [:systematic-review])
                    (= active-panel [:data-extraction])
                    (= active-panel [:managed-review])
                    (= active-panel [:root])))))

(def-data :global-stats
  :loaded? (fn [db] (-> (get-in db [:data])
                        (contains? :global-stats)))
  :uri (constantly "/api/global-stats")
  :process (fn [{:keys [db]} _ {:keys [stats]}]
             (when stats
               {:db (assoc-in db [:data :global-stats] stats)})))

(reg-sub :global-stats #(get-in % [:data :global-stats]))

(defn GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
                (let [{:keys [labeled-articles label-entries real-projects]} @(subscribe [:global-stats])]
                  [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
                   {:style {:max-width "700px" :margin "auto" :margin-top 0}}
                   [:div.column [:p [:span.bold (str real-projects)] " Reviews"]]
                   [:div.column [:p [:span.bold (str labeled-articles)] " Documents Reviewed"]]
                   [:div.column [:p [:span.bold (str label-entries)] " Review Answers"]]]))])

(defn IntroSegment []
  [:div.ui.segment.center.aligned.inverted {:style {:padding-top 50 :padding-bottom 40 :margin-top -13
                                                    :margin-bottom 0 :border-radius 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Built for data miners."]
    [:h2.ui {:style {:margin-top 5 :font-size "20px"}} "SysRev helps humans work together and with machines to extract data from documents."]
    [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
     {:style {:max-width "700px" :margin "auto" :margin-top 0}}
     [:div.column [:h1 [:a {:href "/lit-review"} "Literature Review"]]]
     [:div.column [:h1 [:a {:href "/data-extraction"} "Data Extraction"]]]
     [:div.column [:h1 [:a {:href "/systematic-review"} "Systematic Review"]]]]
    [:h2.ui {:style {:margin-top 5 :font-size "20px"}} "Want help with a big project?"[:br]"Talk to the "[:a {:href "/managed-review"} "Managed Review Division"]]
    [:button.ui.fluid.primary.button {:style {:width 200 :margin "auto" :padding "20px" :margin-top "32px"}
                                      :on-click #(nav "/register")} "Sign up for SysRev"]]
   [:div {:style {:margin-top 50}}
    [:h5 {:style {:margin-bottom 0}} "live updates"]
    [GlobalStatsReport]]])

(defn FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:padding-top 60 :margin-top 0 :border 0 :border-radius 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Learn from the best with open access projects."]
    [:h2.ui "Watch, " [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"] " and learn from successful projects."]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Community Literature Reviews"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}

    [:div.ui.raised.card {:on-click (fn [_] (nav "/p/16612")) :style {:cursor "pointer"}}
     [:div.image [:img {:src "/entogem.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/16612"} "EntoGEM"]
      [:div.meta [:a {:href "https://sysrev.com/p/16612"} "sysrev.com/p/16612"]]
      [:div.description [:p "EntoGEM is a community-driven project that aims to compile evidence about
                    global insect population and biodiversity status and trends. see "
                         [:a {:href "https://entogem.github.io"} "entogem.github.io"]]]]
     [:div.extra.content
      [:span "Eliza Grames"[:br] "University of Connecticut"][:br]
      [:span [:a {:href "https://twitter.com/elizagrames"} [:i.twitter.icon] "ElizaGrames"]]]]
    [:div.ui.raised.card {:on-click (fn [_] (nav "/p/24557")) :style {:cursor "pointer"}}
     [:div.image [:img {:src "/bushfires.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/24557"} "Fire & Australian Invertebrates"]
      [:div.meta [:a {:href "https://sysrev.com/p/24557"} "sysrev.com/p/24557"]]
      [:div.description [:p "Established in response to catastrophic bushfires in Australia,
                    this project seeks to understand how invertebrates respond to fire events."]]]
     [:div.extra.content
      [:span "Manu Saunders" [:br] "University of New England AU"][:br]
      [:span [:a {:href "https://twitter.com/ManuSaunders"} [:i.twitter.icon] "ManuSaunders"]]]]
    [:div.ui.raised.card {:on-click (fn [_] (nav "/p/3588")) :style {:cursor "pointer"}}
     [:div.image [:img {:src "/tumur.png"}]]
     [:div.content
      [:a.header "Cancer Hallmark Mapping"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description [:p "The aim of this project is to identify novel assays and biomarkers
                    that map to the hallmarks of cancer and the key characteristics of carcinogens"]]]
     [:div.extra.content
      [:span "Collaboration at" [:br] "National Toxicology Program “Converging on Cancer” workshop"]]]]
   ])

(defn RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
               [:div.landing-page.landing-public
                [IntroSegment]
                [FeaturedReviews]]))

(defn RootFullPanelUser []
  [:div.landing-page [plist/UserProjectListFull]])

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelUser]))

(defmethod logged-out-content panel []
  [RootFullPanelPublic])
