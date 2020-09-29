(ns sysrev.views.panels.landing-pages.lit-review
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.nav :refer [nav]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:lit-review])

(defn- GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
     (let [{:keys [labeled-articles label-entries real-projects]} @(subscribe [:global-stats])]
       [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
        {:style {:max-width "700px" :margin "auto" :margin-top 0}}
        [:div.column>p [:span.bold (str real-projects)] " Reviews"]
        [:div.column>p [:span.bold (str labeled-articles)] " Documents Reviewed"]
        [:div.column>p [:span.bold (str label-entries)] " Review Answers"]]))])

(defn- IntroSegment []
  [:div.ui.segment.center.aligned.inverted {:style {:padding-top 50 :padding-bottom 40
                                                    :margin-top -13 :margin-bottom 0
                                                    :border-radius 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}}
     "Review Literature for Free."]
    [:h2 {:style {:margin-top 5 :font-size "20px"}}
     "Make a Team." [:br]
     "Upload Citations." [:br]
     "Extract Answers."]
    [:a.ui.fluid.primary.button {:style {:margin "auto" :width 200
                                         :padding "20px" :margin-top "32px"}
                                 :href "/register"}
     "Try for Free"]]
   [:div {:style {:margin-top 50}}
    [:h5 {:style {:margin-bottom 0}} "live updates"]
    [GlobalStatsReport]]])

(defn- FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:border 0 :border-radius 0
                                           :padding-top 60 :margin-top 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}}
     "Want to Learn How?"]
    [:h2 "Check out some of the best public review projects."]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Community Literature Reviews"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}
    [:div.ui.raised.card {:on-click #(nav "/p/16612") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/entogem.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/16612"} "EntoGEM"]
      [:div.meta [:a {:href "https://sysrev.com/p/16612"} "sysrev.com/p/16612"]]
      [:div.description>p
       "EntoGEM is a community-driven project that aims to compile evidence about
            global insect population and biodiversity status and trends. see "
       [:a {:href "https://entogem.github.io"} "entogem.github.io"]]]
     [:div.extra.content
      "Eliza Grames" [:br]
      "University of Connecticut" [:br]
      [:a {:href "https://twitter.com/elizagrames"} [:i.twitter.icon] "ElizaGrames"]]]
    [:div.ui.raised.card {:on-click #(nav "/p/24557") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/bushfires.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/24557"} "Fire & Australian Invertebrates"]
      [:div.meta [:a {:href "https://sysrev.com/p/24557"} "sysrev.com/p/24557"]]
      [:div.description>p
       "Established in response to catastrophic bushfires in Australia,
        this project seeks to understand how invertebrates respond to fire events."]]
     [:div.extra.content
      "Manu Saunders" [:br]
      "University of New England AU" [:br]
      [:a {:href "https://twitter.com/ManuSaunders"} [:i.twitter.icon] "ManuSaunders"]]]
    [:div.ui.raised.card {:on-click #(nav "/p/3588") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/tumur.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/3588"} "Cancer Hallmark Mapping"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description>p
       "The aim of this literature review is to identify novel assays and biomarkers
        that map to the hallmarks of cancer and the key characteristics of carcinogens"]]
     [:div.extra.content
      "Collaboration at" [:br]
      "National Toxicology Program “Converging on Cancer” workshop"]]]])

(defn- RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment]
     [FeaturedReviews]]))

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelPublic]))
