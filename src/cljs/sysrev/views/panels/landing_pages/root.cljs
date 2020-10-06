(ns sysrev.views.panels.landing-pages.root
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.data.core :refer [reload load-data]]
            [sysrev.views.project-list :as plist]
            [sysrev.views.panels.landing-pages.core :refer [IntroSegment FeaturedReviews]]
            [sysrev.views.panels.landing-pages.reviews :as reviews]
            [sysrev.macros :refer-macros [with-loader def-panel]]))

(def panel [:root])

(defn- PanelLoggedIn []
  [:div.landing-page [plist/UserProjectListFull]])

(defn- PanelLoggedOut []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment
      {:titles-1 ["Built for data miners."]
       :titles-2 ["SysRev helps humans work together and with machines to extract data from documents."]
       :description
       (fn [] [:div
               [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
                {:style {:max-width "700px" :margin "auto" :margin-top 0}}
                [:div.column>h1 [:a {:href "/lit-review"} "Literature Review"]]
                [:div.column>h1 [:a {:href "/data-extraction"} "Data Extraction"]]
                [:div.column>h1 [:a {:href "/systematic-review"} "Systematic Review"]]]
               [:h2 {:style {:margin-top 5 :font-size "20px"}}
                "Want help with a big project?" [:br]
                "Talk to the " [:a {:href "/managed-review"} "Managed Review Division"]]])
       :register "Sign up for SysRev"}
      {:projects "Reviews"
       :articles "Documents Reviewed"
       :labels "Review Answers"}]
     [FeaturedReviews
      {:header-1 "Learn from the best with open access projects."
       :header-2 [:span "Watch, "
                  [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"]
                  " and learn from successful projects."]
       :header-3 "Community Literature Reviews"
       :cards [^{:key 1} [reviews/EntogemReview]
               ^{:key 2} [reviews/FireAustralianReview]
               ^{:key 3} [reviews/CancerHallmarkReview]]}]]))

(def-panel :uri "/" :panel panel
  :on-route (do (dispatch [:set-active-panel panel])
                (load-data :identity)
                (reload :public-projects))
  :content [PanelLoggedIn]
  :logged-out-content [PanelLoggedOut])
