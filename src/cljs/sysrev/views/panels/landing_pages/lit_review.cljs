(ns sysrev.views.panels.landing_pages.lit-review
   (:require [cljs-time.core :as time]
             [re-frame.core :refer [subscribe reg-sub]]
             [sysrev.data.core :refer [def-data]]
             [sysrev.views.base :refer [panel-content logged-out-content]]
             [sysrev.views.panels.login :refer [LoginRegisterPanel]]
             [sysrev.views.panels.pricing :refer [Pricing]]
             [sysrev.views.project-list :as plist]
             [sysrev.nav :refer [nav]]
             [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:lit-review])

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
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Start reviewing literature for free."]
    [:h2.ui {:style {:margin-top 5 :font-size "20px"}} "There are hundreds of literature reviews on SysRev. Join one or start your own. "]
    [:button.ui.fluid.primary.button {:style {:width 200 :margin "auto" :padding "20px" :margin-top "32px"}
                                      :on-click #(nav "/register")} "Sign up for SysRev"]]
    [:div {:style {:margin-top 50}}
     [:h5 {:style {:margin-bottom 0}} "live updates"]
     [GlobalStatsReport]]])

(defn FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:padding-top 60 :margin-top 0 :border 0 :border-radius 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Learn from the best with open access projects."]
    [:h2.ui "Watch, learn and " [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"] " successful literature reviews."]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Community Literature Reviews"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}

    [:div.ui.raised.card
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
    [:div.ui.raised.card
     [:div.image [:img {:src "/bushfires.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/24557"} "Fire & Australian Invertebrates"]
      [:div.meta [:a {:href "https://sysrev.com/p/24557"} "sysrev.com/p/24557"]]
      [:div.description [:p "Established in response to catastrophic bushfires in Australia,
                    this project seeks to understand how invertebrates respond to fire events."]]]
     [:div.extra.content
      [:span "Manu Saunders" [:br] "University of New England AU"][:br]
      [:span [:a {:href "https://twitter.com/ManuSaunders"} [:i.twitter.icon] "ManuSaunders"]]]]
    [:div.ui.raised.card
     [:div.image [:img {:src "/tumur.png"}]]
     [:div.content
      [:a.header "Cancer Hallmark Mapping"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description [:p "The aim of this literature review is to identify novel assays and biomarkers
                    that map to the hallmarks of cancer and the key characteristics of carcinogens"]]]
     [:div.extra.content
      [:span "Collaboration at" [:br] "National Toxicology Program “Converging on Cancer” workshop"]]]]
   ])


(defn GetStarted []
  [:div.ui.segment.center.aligned.inverted {:style {:margin-top 0 :border-radius 0 :border 0}}
   [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Get Started"]
   [:h3.ui {:style {:margin-top 5 :font-size "20px"}} "Learn to " [:a {:href "https://blog.sysrev.com/getting-started/"} " make a SysRev in 5 steps"] " or watch the video below"]
   [:iframe {:width "560" :height "315" :src "https://www.youtube.com/embed/dHISlGOm7A8" :frameborder "0" :allow "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" :allowfullscreen "true"}]
   [:button.ui.fluid.primary.button {:style {:width 200 :margin "auto" :padding "20px" :margin-top "32px"} :on-click #(nav "/register")} "Sign up for SysRev"]])

(defn RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
               [:div.landing-page.landing-public
                [IntroSegment]
                [FeaturedReviews]
                [GetStarted]]))

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelPublic]))
