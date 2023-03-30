(ns sysrev.views.panels.landing-pages.managed-review
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [sysrev.action.core :refer [def-action run-action]]
            [sysrev.data.core :refer [reload load-data]]
            [sysrev.views.components.core :refer [UrlLink]]
            [sysrev.views.semantic :as S]
            [sysrev.views.panels.landing-pages.core :refer
             [IntroSegment FeaturedReviews ReviewCard TwitterUser]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [with-loader def-panel]]
            [sysrev.base :as base]))

(def panel [:managed-review])

(reg-sub      ::is-submitted #(::is-submitted %))
(reg-event-db ::is-submitted
              (fn [db [_ value]] (assoc db ::is-submitted value)))

(def-action :request-managed-review
  :uri (constantly "/api/managed-review-request")
  :content (fn [{:keys [name email description] :as values}]
             values)
  :process (fn [_ _ _] {}))

(defn- request-review []
  (run-action :request-managed-review
              {:name (.-value (js/document.querySelector "form.request-review input.xname"))
               :email (.-value (js/document.querySelector "form.request-review input.xemail"))
               :description (.-value (js/document.querySelector "form.request-review textarea.xdesc"))}))

(defn- InputForm []
  (let [on-submit (util/wrap-prevent-default
                   (fn [_]
                     (dispatch [::is-submitted true])
                     (request-review)))]
    (if (not @(subscribe [::is-submitted]))
      [:div.ui.raised.segment.left.aligned {:style {:max-width "500px" :margin "auto"}}
       [S/Header {:as "h3" :align "center" :style {:padding-bottom "20px"}}
        "Tell us more and we will contact you shortly"]
       [:form.ui.form.request-review {:on-submit on-submit}
        [:div.field
         [:label "Name"]
         [:input.xname {:type "text" :name "Name" :placeholder
                        "What should we call you?"}]]
        [:div.field
         [:label "Email"]
         [:input.xemail {:type "text" :name "Email"
                         :placeholder "How do we contact you?"}]]
        [:div.field
         [:label "Describe your project"]
         [:textarea.xdesc {:placeholder (str "Where do you want to extract data from?\n"
                                             "What do you want to extract?\n"
                                             "What else should we know?")}]]
        [:button.ui.button.primary {:type "submit"} "Submit"]]]
      [:div.ui.raised.segment {:style {:max-width "500px" :margin "auto"}}
       [:b "Thank you for your request, we will be in contact soon"]])))

(defn- MangiferinReview []
  [ReviewCard
   {:href "/p/21696"
    :header "Extracting Mangiferin Effects" :img "/mangiferin-clustering.png"
    :description
    [:p "SysRev helps companies extract therapeutic effects of substances from literature.
         In this pilot, we show how mangiferin (a mango extract) modifies disease."
     (when @base/show-blog-links
       [:br])
     (when @base/show-blog-links
       [UrlLink "https://blog.sysrev.com/generating-insights"])]
    ;; TODO can we link to beiersdorf
    :extra ["TJ Bozada"
            "Insilica Managed Review Division"
            [:span [:i.envelope.icon] "info@insilica.co"]]}])

(defn- SDSReview []
  [ReviewCard
   {:href "/p/4047"
    :header "Safety Data Sheet Extraction" :img "/sds.png"
    :description
    [:p "Safety Data Sheets lock chemical information into pdfs. "
     "SysRev Managed Review worked with the Sustainable Research Group to extract that data
      into spreadsheets to help SRG clients."
     (when @base/show-blog-links
       [:br])
     (when @base/show-blog-links
       [UrlLink "https://blog.sysrev.com/srg-sysrev-chemical-transparency"])]
    :extra ["Daniel Mcgee"
            [:a {:href "https://sustainableresearchgroup.com"}
             "Sustainable Research Group"]]}])

(defn- GeneHunterReview []
  [ReviewCard
   {:href "/p/3144"
    :header "Gene Hunter" :img "/genehunter.png"
    :description
    [:p "Gene Hunter extracted genes from text to create a "
     [:b "named entity recognition"] " model. "
     "Data extraction the first step in creating machine learning models, this project shows
      how sysrev builds models."
     (when @base/show-blog-links
       [:br])
     (when @base/show-blog-links
       [UrlLink "https://blog.sysrev.com/simple-ner"])]
    :extra ["Tom Luechtefeld"
            [UrlLink "https://insilica.co"]
            [TwitterUser "tomlue"]]}])

(defn- Panel []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment
      {:titles-1 ["Need help with data extraction?"]
       :description (fn [] [InputForm])}]
     [FeaturedReviews
      {:header-1 "Existing Projects"
       :header-2 "We've helped companies extract millions of data points. How can we help you?"
       :header-3 "Public Data Extraction Projects"
       :cards [^{:key 1} [MangiferinReview]
               ^{:key 2} [SDSReview]
               ^{:key 3} [GeneHunterReview]]}]]))

(def-panel :uri "/managed-review" :panel panel
  :on-route (do (dispatch [:set-active-panel panel])
                (load-data :identity)
                (reload :public-projects))
  :content [Panel])
