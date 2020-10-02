(ns sysrev.views.panels.landing-pages.systematic-review
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.data.core :refer [reload load-data]]
            [sysrev.views.panels.landing-pages.core :refer [IntroSegment FeaturedReviews]]
            [sysrev.views.panels.landing-pages.reviews :as reviews]
            [sysrev.macros :refer-macros [with-loader def-panel]]))

(def panel [:systematic-review])

(defn- Panel []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment
      {:titles-1 ["Built for Systematic Review."]
       :titles-2 ["Search the literature."
                  "Upload research documents."
                  "Add friends and review."]
       :register "Sign up for SysRev"}
      {:projects "Reviews"
       :articles "Documents Reviewed"
       :labels "Review Answers"}]
     [FeaturedReviews
      {:header-1 "Learn from the best with open access projects."
       :header-2 [:span
                  [:a {:href "/search"} "Discover"] " and "
                  [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"]
                  " public systematic reviews." [:br]
                  "Publish your work with a simple link."]
       :header-3 "Community Systematic Reviews"
       :cards [^{:key 1} [reviews/CancerHallmarkReview]
               ^{:key 2} [reviews/CovidKidneyReview]
               ^{:key 3} [reviews/VitaminCCancerReview]]}]]))

(def-panel {:uri "/systematic-review"
            :on-route (do (dispatch [:set-active-panel panel])
                          (load-data :identity)
                          (reload :public-projects))
            :panel panel :content [Panel]})
