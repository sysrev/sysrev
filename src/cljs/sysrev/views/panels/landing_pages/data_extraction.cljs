(ns sysrev.views.panels.landing_pages.data-extraction
   (:require [cljs-time.core :as time]
             [re-frame.core :refer [subscribe reg-sub]]
             [sysrev.data.core :refer [def-data]]
             [sysrev.views.base :refer [panel-content logged-out-content]]
             [sysrev.views.panels.login :refer [LoginRegisterPanel]]
             [sysrev.views.panels.pricing :refer [Pricing]]
             [sysrev.views.project-list :as plist]
             [sysrev.nav :refer [nav]]
             [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:data-extraction])

(defn GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
                (let [{:keys [labeled-articles label-entries real-projects]} @(subscribe [:global-stats])]
                  [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
                   {:style {:max-width "700px" :margin "auto" :margin-top 0}}
                   [:div.column [:p [:span.bold (str real-projects)] " Data Extraction Projects"]]
                   [:div.column [:p [:span.bold (str labeled-articles)] " Documents Reviewed"]]
                   [:div.column [:p [:span.bold (str label-entries)] " Labels Extracted"]]]))])

(defn IntroSegment []
   [:div.ui.segment.center.aligned.inverted {:style {:padding-top 50 :padding-bottom 40 :margin-top -13
                                                     :margin-bottom 0 :border-radius 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Extract Data."]
    [:h2.ui {:style {:margin-top 5 :font-size "20px"}} "Upload Documents."[:br] "Recruit Reviewers."[:br]"Define Labels."[:br]"Extract Answers."]
    [:button.ui.fluid.primary.button {:style {:width 200 :margin "auto" :padding "20px" :margin-top "32px"}
                                      :on-click #(nav "/register")} "Sign up for SysRev"]]
    [:div {:style {:margin-top 50}}
     [:h5 {:style {:margin-bottom 0}} "live updates"]
     [GlobalStatsReport]]])

(defn FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:padding-top 60 :margin-top 0 :border 0 :border-radius 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Share projects with a link."]
    [:h2.ui "Share your own project or find and " [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"] " other data extraction projects."]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Open Access Data Extraction Projects"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}

    [:div.ui.raised.card
     [:div.image [:img {:src "/genehunter.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/3588"} "Gene Hunter"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description [:p "Gene Hunter extracted gene names from medical abstracts to create a " [:b "named entity recognition"] " model. Learn more at "
                         [:a {:href "https://blog.sysrev.com/simple-ner"} "blog.sysrev.com/simple-ner"]]]]
     [:div.extra.content
      [:span "Tom Luechtefeld"[:br] [:a {:href "https://insilica.co"} "Insilica.co"][:br]
      [:span [:a {:href "https://twitter.com/tomlue"} [:i.twitter.icon] "tomlue"]]]]]
    [:div.ui.raised.card
     [:div.image [:img {:src "/sds.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/4047"} "Safety Data Sheet Extraction"]
      [:div.meta [:a {:href "https://sysrev.com/p/4047"} "sysrev.com/p/24557"]]
      [:div.description [:p "SDS PDFs lock important chemical information into pdfs. Sysrev was used to extract that data into spreadsheets "
                         [:a {:href "https://blog.sysrev.com/srg-sysrev-chemical-transparency/"} "Learn more at blog.sysrev.com"]]]]
     [:div.extra.content
      [:span "Daniel Mcgee" [:br] [:a {:href "https://sustainableresearchgroup.com/"} "Sustainable Research Group"][:br]]]]
    [:div.ui.raised.card
     [:div.image [:img {:src "/mangiferin-clustering.png"}]]
     [:div.content
      [:a.header "Extracting Mangiferin Effects"]
      [:div.meta [:a {:href "https://sysrev.com/p/21696"} "sysrev.com/p/21696"]]
      [:div.description [:p "An extraction of mangiferin (a mango extract) effects from publications. R and "
                         [:a {:href "https://github.com/sysrev/RSysrev"} "RSysrev"] " were used to analyze results. "
                         [:a {:href "https://blog.sysrev.com/generating-insights/"} "blog.sysrev.com/generating-insights"]]]]
     [:div.extra.content ;TODO make this a link to managed review
      [:span "TJ Bozada" [:br] "Insilica Managed Review Division"[:br] [:i.envelope.icon] "info@insilica.co"]]]]
   ])

(defn GetStarted []
  [:div.ui.segment.center.aligned.inverted {:style {:margin-top 0 :border-radius 0 :border 0}}
   [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Get Started"]
   [:h3.ui {:style {:margin-top 5 :font-size "20px"}} "Learn to " [:a {:href "https://blog.sysrev.com/getting-started/"} " make a SysRev in 5 steps"] " or watch the video below"]
   [:iframe {:width "560" :height "315" :src "https://www.youtube.com/embed/dHISlGOm7A8" :frameborder "0" :allow "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" :allowfullscreen "true"}]
   [:button.ui.fluid.primary.button {:style {:width 200 :margin "auto" :padding "20px" :margin-top "32px"}
                                     :on-click #(nav "/register")} "Sign up for SysRev"]
   ])

(defn RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
               [:div.landing-page.landing-public
                [IntroSegment]
                [FeaturedReviews]
                [GetStarted]]))

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelPublic]))
