(ns sysrev.views.panels.landing-pages.systematic-review
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.nav :refer [nav]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:systematic-review])

(defn- GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
     (let [{:keys [labeled-articles label-entries real-projects]} @(subscribe [:global-stats])]
       [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
        {:style {:max-width "700px" :margin "auto" :margin-top 0}}
        [:div.column [:p [:span.bold (str real-projects)] " Reviews"]]
        [:div.column [:p [:span.bold (str labeled-articles)] " Documents Reviewed"]]
        [:div.column [:p [:span.bold (str label-entries)] " Review Answers"]]]))])

(defn- IntroSegment []
  [:div.ui.segment.center.aligned.inverted
   {:style {:padding-top 50 :padding-bottom 40
            :margin-top -13 :margin-bottom 0
            :border-radius 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}}
     "Built for Systematic Review."]
    [:h2 {:style {:margin-top 5 :font-size "20px"}}
     "Search the literature." [:br]
     "Upload research documents." [:br]
     "Add friends and review."]
    [:a.ui.fluid.primary.button {:style {:width 200 :margin "auto"
                                         :padding "20px" :margin-top "32px"}
                                 :href "/register"}
     "Sign up for SysRev"]]
   [:div {:style {:margin-top 50}}
    [:h5 {:style {:margin-bottom 0}} "live updates"]
    [GlobalStatsReport]]])

(defn- FeaturedReviews []
  [:div.ui.segment.center.aligned
   {:style {:padding-top 60 :margin-top 0 :margin-bottom 0
            :border 0 :border-radius 0 }}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}}
     "Learn from the best with open access projects."]
    [:h2 [:a {:href "/search"} "Discover"] " and "
     [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"]
     " public systematic reviews." [:br]
     "Publish your work with a simple link."]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Community Systematic Reviews"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}
    [:div.ui.raised.card {:on-click #(nav "/p/3588") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/tumur.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/3588"} "Cancer Hallmark Mapping"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description>p
       "The aim of this systematic review is to identify novel assays and biomarkers
        that map to the hallmarks of cancer and the key characteristics of carcinogens"]]
     [:div.extra.content
      "Collaboration at" [:br]
      "National Toxicology Program “Converging on Cancer” workshop"]]
    [:div.ui.raised.card {:on-click #(nav "/p/29506") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/ckd-covid.png"}]]
     [:div.content
      [:a.header {:href "/p/29506"} "COVID-19 Kidney Disease"]
      [:div.meta [:a {:href "https://sysrev.com/p/29506"} "sysrev.com/p/29506"]]
      [:div.description>p
       "A multinational team used Sysrev to assess the clinical characteristics and the risk factors associated with SARS CoV2 in patients with Chronic Kidney Disease."]]
     [:div.extra.content
      "Ciara Keenan" [:br]
      "Associate Director - Cochrane Ireland" [:br]
      [:a {:href "https://twitter.com/MetaEvidence"} [:i.twitter.icon] "MetaEvidence"]]]
    [:div.ui.raised.card {:on-click #(nav "/p/6737") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/vitc.png"}]]
     [:div.content
      [:a.header {:href "/p/6737"} "Vitamin C Cancer Trials"]
      [:div.meta [:a {:href "/p/6737"} "sysrev.com/p/6737"]]
      [:div.description>p
       "A systematic review of clinicaltrials.gov measuring drugs and dosing in ascorbate cancer trials. "
       [:a {:href "https://scholar.google.com/scholar?cluster=16503083734790425316&hl=en&oi=scholarr"}
        "Vitamin C and Cancer: An Overview of Recent Clinical Trials"]]]
     [:div.extra.content
      "Channing Paller" [:br]
      "Johns Hopkins School of Medicine." [:br]
      "with collaborators at EMMES"]]]])

(defn- RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment]
     [FeaturedReviews]]))

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelPublic]))
