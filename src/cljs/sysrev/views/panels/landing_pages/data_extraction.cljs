(ns sysrev.views.panels.landing-pages.data-extraction
  (:require [re-frame.core :refer [subscribe]]
            [sysrev.nav :refer [nav]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.macros :refer-macros [with-loader]]))

(def ^:private panel [:data-extraction])

(defn- GlobalStatsReport []
  [:div.global-stats
   (with-loader [[:global-stats]] {}
     (let [{:keys [labeled-articles label-entries real-projects]} @(subscribe [:global-stats])]
       [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
        {:style {:max-width "700px" :margin "auto" :margin-top 0}}
        [:div.column>p [:span.bold (str real-projects)] " Data Extraction Projects"]
        [:div.column>p [:span.bold (str labeled-articles)] " Documents Reviewed"]
        [:div.column>p [:span.bold (str label-entries)] " Labels Extracted"]]))])

(defn- IntroSegment []
  [:div.ui.segment.center.aligned.inverted {:style {:padding-top 50 :padding-bottom 40
                                                    :margin-top -13 :margin-bottom 0
                                                    :border-radius 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}}
     "Extract Data."]
    [:h2 {:style {:margin-top 5 :font-size "20px"}}
     "Upload Documents." [:br]
     "Recruit Reviewers." [:br]
     "Define Labels." [:br]
     "Extract Answers."]
    [:a.ui.fluid.primary.button {:style {:width 200 :margin "auto" :padding "20px" :margin-top "32px"}
                                 :href "/register"}
     "Sign up for SysRev"]]
   [:div {:style {:margin-top 50}}
    [:h5 {:style {:margin-bottom 0}} "live updates"]
    [GlobalStatsReport]]])

(defn- FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:border 0 :border-radius 0 :padding-top 60
                                           :margin-top 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}}
     "Share projects with a link."]
    [:h2 "Share your own project or find and "
     [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"]
     " other data extraction projects."]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}}
    "Open Access Data Extraction Projects"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}
    [:div.ui.raised.card {:on-click #(nav "/p/3588") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/genehunter.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/3588"} "Gene Hunter"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description>p
       "Gene Hunter extracted gene names from medical abstracts to create a "
       [:b "named entity recognition"] " model. Learn more at "
       [:a {:href "https://blog.sysrev.com/simple-ner"} "blog.sysrev.com/simple-ner"]]]
     [:div.extra.content
      "Tom Luechtefeld" [:br]
      [:a {:href "https://insilica.co"} "Insilica.co"] [:br]
      [:a {:href "https://twitter.com/tomlue"} [:i.twitter.icon] "tomlue"]]]
    [:div.ui.raised.card {:on-click #(nav "/p/4047") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/sds.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/4047"} "Safety Data Sheet Extraction"]
      [:div.meta [:a {:href "https://sysrev.com/p/4047"} "sysrev.com/p/24557"]]
      [:div.description>p
       "SDS PDFs lock important chemical information into pdfs. "
       "Sysrev was used to extract that data into spreadsheets "
       [:a {:href "https://blog.sysrev.com/srg-sysrev-chemical-transparency/"}
        "Learn more at blog.sysrev.com"]]]
     [:div.extra.content
      "Daniel Mcgee" [:br]
      [:a {:href "https://sustainableresearchgroup.com/"}
       "Sustainable Research Group"]]]
    [:div.ui.raised.card {:on-click #(nav "/p/21696") :style {:cursor "pointer"}}
     [:div.image [:img {:src "/mangiferin-clustering.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/21696"} "Extracting Mangiferin Effects"]
      [:div.meta [:a {:href "https://sysrev.com/p/21696"} "sysrev.com/p/21696"]]
      [:div.description>p
       "An extraction of mangiferin (a mango extract) effects from publications. R and "
       [:a {:href "https://github.com/sysrev/RSysrev"} "RSysrev"]
       " were used to analyze results. "
       [:a {:href "https://blog.sysrev.com/generating-insights/"}
        "blog.sysrev.com/generating-insights"]]]
     [:div.extra.content
      ;; TODO make this a link to managed review
      "TJ Bozada" [:br]
      "Insilica Managed Review Division" [:br]
      [:i.envelope.icon] "info@insilica.co"]]]])

(defn- RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment]
     [FeaturedReviews]]))

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelPublic]))
