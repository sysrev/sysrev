(ns sysrev.views.panels.landing-pages.managed-review
  (:require
    ["jquery" :as $]
    [ajax.core :refer [GET POST PUT DELETE]]
    [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
    [cljs-time.core :as time]
    [re-frame.core :refer [subscribe reg-sub]]
    [sysrev.data.core :refer [def-data]]
    [sysrev.views.base :refer [panel-content logged-out-content]]
    [sysrev.views.panels.login :refer [LoginRegisterPanel]]
    [sysrev.views.panels.pricing :refer [Pricing]]
    [sysrev.views.project-list :as plist]
    [sysrev.macros :refer-macros [with-loader]]
    [sysrev.views.semantic :refer [Form FormRadio FormGroup Grid Input Row Column Button Icon Radio]]
    [sysrev.util :as util]))

(def ^:private panel [:managed-review])

(reg-event-db ::set-is-submitted (fn [db [_ value]] (assoc db ::is-submitted value)))
(reg-sub ::is-submitted (fn [db _] (::is-submitted db)))

(defn send-managed-review-request [name email description]
  (POST (str "/api/managed-review-request")
        {:headers       {"x-csrf-token" @(subscribe [:csrf-token])}
         :params        {:name name :email email :description description}
         :handler       (fn [_response] (util/log "done!"))
         :error-handler (fn [response] (util/log (str response)))
         }))

(defn- InputForm []
  (let [on-submit (util/wrap-prevent-default
                    (fn [_]
                      (let [name (.val ($ "#nameinput"))
                            email (.val ($ "#emailinput"))
                            description (.val ($ "#descriptioninput"))]
                        (dispatch [::set-is-submitted true])
                        (send-managed-review-request name email description))))]
    (if (not @(subscribe [::is-submitted]))
    [:div.ui.raised.segment.left.aligned {:style { :max-width "500px" :margin "auto"}}
     [:div.ui {:style {:text-align "center" :padding-bottom 20}} [:h3.ui "Tell us more and we will contact you shortly"]]
     [:form.ui.form {:on-submit on-submit}
      [:div.field
       [:label "Name"]
       [:input {:id "nameinput" :type "text" :name "Name" :placeholder "What should we call you?"}]]
      [:div.field
       [:label "Email"]
       [:input {:id "emailinput" :type "text" :name "Email" :placeholder "How do we contact you?"}]]
      [:div.field
       [:label "Describe your project"]
       [:textarea {:id "descriptioninput"
                   :placeholder "Where do you want to extract data from?\nWhat do you want to extract?\nWhat else should we know?"}]]
      [:button.ui.button.primary {:type "submit"} "Submit"]]]
    [:div.ui.raised.segment {:style { :max-width "500px" :margin "auto"}} [:b "Thank you for your request, we will be in contact soon"]])))

(defn IntroSegment []
   [:div.ui.segment.center.aligned.inverted {:style {:padding-top 50 :padding-bottom 50 :margin-top -13
                                                     :margin-bottom 0 :border-radius 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "500px" :margin-bottom 20}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Need help with data extraction?"]
    [InputForm]]])

(defn FeaturedReviews []
  [:div.ui.segment.center.aligned {:style {:padding-top 60 :margin-top 0 :border 0 :border-radius 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    [:h1.ui {:style {:margin-top 5 :font-size "48px"}} "Existing Projects"]
    [:h2.ui "We've helped companies extract millions of data points. How can we help you?"]]
   [:h3.ui.top.attached {:style {:margin-bottom 0}} "Public Data Extraction Projects"]
   [:div.ui.attached.three.stackable.cards {:style {:max-width "1000px" :margin "auto"}}
    [:div.ui.raised.card
     [:div.image [:img {:src "/mangiferin-clustering.png"}]]
     [:div.content
      [:a.header "Extracting Mangiferin Effects"]
      [:div.meta [:a {:href "https://sysrev.com/p/21696"} "sysrev.com/p/21696"]]
      [:div.description
       [:p "SysRev helps companies extract therapeutic effects of substances from literature.
           In this pilot, we show how mangiferin (a mango extract) modifies disease."[:br]
        [:a {:href "https://blog.sysrev.com/generating-insights/"} "blog.sysrev.com/generating-insights"]]]]
     [:div.extra.content ;TODO can we link to beiersdorf
      [:span "TJ Bozada" [:br] "Insilica Managed Review Division"[:br] [:i.envelope.icon] "info@insilica.co"]]]
   [:div.ui.raised.card
    [:div.image [:img {:src "/sds.png"}]]
    [:div.content
     [:a.header {:href "https://sysrev.com/p/4047"} "Safety Data Sheet Extraction"]
     [:div.meta [:a {:href "https://sysrev.com/p/4047"} "sysrev.com/p/24557"]]
     [:div.description [:p "Safety Data Sheets lock chemical information into pdfs.
     SysRev Managed Review worked with the Sustainable Research Group to extract that data into spreadsheets to help SRG clients."[:br]
                        [:a {:href "https://blog.sysrev.com/srg-sysrev-chemical-transparency/"} "SRG Chemical Transparency"]]]]
    [:div.extra.content
     [:span "Daniel Mcgee" [:br] [:a {:href "https://sustainableresearchgroup.com/"} "Sustainable Research Group"][:br]]]]
    [:div.ui.raised.card
     [:div.image [:img {:src "/genehunter.png"}]]
     [:div.content
      [:a.header {:href "https://sysrev.com/p/3588"} "Gene Hunter"]
      [:div.meta [:a {:href "https://sysrev.com/p/3588"} "sysrev.com/p/3588"]]
      [:div.description [:p "Gene Hunter extracted genes from text to create a " [:b "named entity recognition"] " model.
      Data extraction the first step in creating mmachine learning models, this project shows how sysrev builds models. "[:br]
                         [:a {:href "https://blog.sysrev.com/simple-ner"} "blog.sysrev.com/simple-ner"]]]]
     [:div.extra.content
      [:span "Tom Luechtefeld"[:br] [:a {:href "https://insilica.co"} "Insilica.co"][:br]
       [:span [:a {:href "https://twitter.com/tomlue"} [:i.twitter.icon] "tomlue"]]]]]]])

(defn RootFullPanelPublic []
  (with-loader [[:identity] [:public-projects] [:global-stats]] {}
               [:div.landing-page.landing-public
                [IntroSegment]
                [FeaturedReviews]]))

(defmethod panel-content panel []
  (fn [_child] [RootFullPanelPublic]))
