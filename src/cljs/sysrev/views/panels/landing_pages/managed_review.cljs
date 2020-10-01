(ns sysrev.views.panels.landing-pages.managed-review
  (:require ["jquery" :as $]
            [sysrev.action.core :refer [def-action run-action]]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [sysrev.data.core :refer [reload load-data]]
            [sysrev.views.semantic :as S]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [with-loader def-panel]]))

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
              {:name (.val ($ "form.request-review input.xname"))
               :email (.val ($ "form.request-review input.xemail"))
               :description (.val ($ "form.request-review textarea.xdesc"))}))

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

(defn- IntroSegment []
  [:div.ui.segment.center.aligned.inverted
   {:style {:padding-top 50 :padding-bottom 50 :margin-top -13
            :margin-bottom 0 :border-radius 0}}
   [:div.description.wrapper.open-sans
    {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}} "Need help with data extraction?"]
    [InputForm]]])

(defn- FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:padding-top 60 :margin-top 0 :margin-bottom 0
                                           :border 0 :border-radius 0 }}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1 {:style {:margin-top 5 :font-size "48px"}} "Existing Projects"]
    [:h2 "We've helped companies extract millions of data points. How can we help you?"]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Public Data Extraction Projects"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}
    [:div.ui.raised.card
     [:div.image [:img {:src "/mangiferin-clustering.png"}]]
     [:div.content
      [:a.header "Extracting Mangiferin Effects"]
      [:div.meta [:a {:href "https://sysrev.com/p/21696"} "sysrev.com/p/21696"]]
      [:div.description
       [:p "SysRev helps companies extract therapeutic effects of substances from literature.
           In this pilot, we show how mangiferin (a mango extract) modifies disease."
        [:br] [:a {:href "https://blog.sysrev.com/generating-insights/"}
               "blog.sysrev.com/generating-insights"]]]]
     [:div.extra.content
      ;; TODO can we link to beiersdorf
      "TJ Bozada" [:br]
      "Insilica Managed Review Division" [:br]
      [:i.envelope.icon] "info@insilica.co"]]
    [:div.ui.raised.card
     [:div.image [:img {:src "/sds.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/4047"} "Safety Data Sheet Extraction"]
      [:div.meta [:a {:href "https://sysrev.com/p/4047"} "sysrev.com/p/24557"]]
      [:div.description>p
       "Safety Data Sheets lock chemical information into pdfs. "
       "SysRev Managed Review worked with the Sustainable Research Group to extract that data into spreadsheets to help SRG clients." [:br]
       [:a {:href "https://blog.sysrev.com/srg-sysrev-chemical-transparency/"}
        "SRG Chemical Transparency"]]]
     [:div.extra.content
      "Daniel Mcgee" [:br]
      [:a {:href "https://sustainableresearchgroup.com/"}
       "Sustainable Research Group"]]]
    [:div.ui.raised.card
     [:div.image [:img {:src "/genehunter.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/3588"} "Gene Hunter"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description>p
       "Gene Hunter extracted genes from text to create a "
       [:b "named entity recognition"] " model. "
       "Data extraction the first step in creating mmachine learning models, this project shows how sysrev builds models." [:br]
       [:a {:href "https://blog.sysrev.com/simple-ner"}
        "blog.sysrev.com/simple-ner"]]]
     [:div.extra.content
      "Tom Luechtefeld" [:br]
      [:a {:href "https://insilica.co"} "Insilica.co"] [:br]
      [:a {:href "https://twitter.com/tomlue"} [:i.twitter.icon] "tomlue"]]]]])

(defn- Panel []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
    [:div.landing-page.landing-public
     [IntroSegment]
     [FeaturedReviews]]))

(def-panel {:uri "/managed-review"
            :on-route (do (dispatch [:set-active-panel panel])
                          (load-data :identity)
                          (reload :public-projects))
            :panel panel :content [Panel]})
