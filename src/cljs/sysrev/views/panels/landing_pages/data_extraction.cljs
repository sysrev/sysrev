(ns sysrev.views.panels.landing-pages.data-extraction
  (:require [re-frame.core :refer [dispatch]]
            [sysrev.data.core :refer [reload load-data]]
            [sysrev.views.panels.landing-pages.core :refer [IntroSegment FeaturedReviews]]
            [sysrev.views.panels.landing-pages.reviews :as reviews]
            [sysrev.macros :refer-macros [with-loader def-panel]]
            [sysrev.base :as base]))

(def panel [:data-extraction])

(defn- Panel []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment
      {:titles-1 ["Extract Data."]
       :titles-2 ["Upload Documents."
                  "Recruit Reviewers."
                  "Define Labels."
                  "Extract Answers."]
       :register "Sign up for SysRev"}
      {:projects "Data Extraction Projects"
       :articles "Documents Reviewed"
       :labels "Labels Extracted"}]
     [FeaturedReviews
      {:header-1 "Share projects with a link."
       :header-2 [:span "Share your own project or find and "
                  (if @base/show-blog-links
                    [:a {:href "https://blog.sysrev.com/cloning-projects"} "clone"]
                    "clone")
                  " other data extraction projects."]
       :header-3 "Open Access Data Extraction Projects"
       :cards [^{:key 1} [reviews/GeneHunterReview]
               ^{:key 2} [reviews/SDSReview]
               ^{:key 3} [reviews/MangiferinReview]]}]]))

(def-panel :uri "/data-extraction" :panel panel
  :on-route (do (dispatch [:set-active-panel panel])
                (load-data :identity)
                (reload :public-projects))
  :content [Panel])
