(ns sysrev.views.panels.landing-pages.lit-review
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.data.core :refer [reload load-data]]
            [sysrev.views.panels.landing-pages.core :refer [IntroSegment FeaturedReviews]]
            [sysrev.views.panels.landing-pages.reviews :as reviews]
            [sysrev.macros :refer-macros [def-panel with-loader]]))

(def panel [:lit-review])

(defn- Panel []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment
      {:titles-1 ["Review Literature for Free."]
       :titles-2 ["Make a Team."
                  "Upload Citations."
                  "Extract Answers."]
       :register "Try for Free"}
      {:projects "Reviews"
       :articles "Documents Reviewed"
       :labels "Review Answers"}]
     [FeaturedReviews
      {:header-1 "Want to Learn How?"
       :header-2 "Check out some of the best public review projects."
       :header-3 "Community Literature Reviews"
       :cards [^{:key 1} [reviews/EntogemReview]
               ^{:key 2} [reviews/FireAustralianReview]
               ^{:key 3} [reviews/CancerHallmarkReview]]}]]))

(def-panel {:uri "/lit-review"
            :on-route (do (dispatch [:set-active-panel panel])
                          (load-data :identity)
                          (reload :public-projects))
            :panel panel :content [Panel]})
