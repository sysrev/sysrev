(ns sysrev.views.panels.landing-pages.core
  (:require [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.nav :as nav]
            [sysrev.views.components.core :refer [UrlLink]]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [with-loader]]))

(reg-sub :landing-page?
         :<- [:active-panel]
         :<- [:self/logged-in?]
         (fn [[active-panel logged-in?]]
           (and (not logged-in?)
                (->> active-panel (contains? #{[:root]
                                               [:lit-review]
                                               [:systematic-review]
                                               [:data-extraction]
                                               [:managed-review]})))))

(def-data :global-stats
  :loaded?  (fn [db] (-> (get-in db [:data])
                         (contains? :global-stats)))
  :uri      (constantly "/api/global-stats")
  :process  (fn [{:keys [db]} _ {:keys [stats]}]
              (when stats
                {:db (assoc-in db [:data :global-stats] stats)})))

(reg-sub :global-stats #(get-in % [:data :global-stats]))

(defn TwitterUser [username]
  [:a {:href (str "https://twitter.com/" username)}
   [:i.twitter.icon] username])

(defn- GlobalStatsReport [{:keys [projects articles labels] :as _titles}]
  [:div.global-stats
   (with-loader [[:global-stats]] {}
     (let [{:keys [labeled-articles label-entries real-projects]} @(subscribe [:global-stats])]
       [:div.ui.three.column.middle.aligned.center.aligned.stackable.grid
        {:style {:max-width "700px" :margin "auto" :margin-top 0}}
        [:div.column>p [:span.bold (str real-projects)] " " projects]
        [:div.column>p [:span.bold (str labeled-articles)] " " articles]
        [:div.column>p [:span.bold (str label-entries)] " " labels]]))])

(defn IntroSegment [{:keys [titles-1 titles-2 description register] :as _config}
                    {:keys [project articles labels] :as stats-titles}]
  [:div.ui.segment.center.aligned.inverted {:style {:padding-top 50 :padding-bottom 40
                                                    :margin-top -13 :margin-bottom 0
                                                    :border-radius 0}}
   [:div.description.wrapper.open-sans
    {:style {:margin "auto" :max-width "600px" :margin-bottom 20}}
    (when (seq titles-1)
      [:h1 {:style {:margin-top 5 :font-size "48px"}}
       (doall (for [[i x] (map-indexed vector titles-1)]
                ^{:key i} [:span (str x) (when-not (= (inc i) (count titles-1))
                                           [:br])]))])
    (when (seq titles-2)
      [:h2 {:style {:margin-top 5 :font-size "20px"}}
       (doall (for [[i x] (map-indexed vector titles-2)]
                ^{:key i} [:span (str x) (when-not (= (inc i) (count titles-2))
                                           [:br])]))])
    (when description
      [description])
    (when register
      [:a.ui.fluid.primary.button {:style {:margin "auto" :width 200
                                           :padding "20px" :margin-top "32px"}
                                   :href "/register"}
       (str register)])]
   (when stats-titles
     [:div {:style {:margin-top 50}}
      [:h5 {:style {:margin-bottom 0}} "live updates"]
      [GlobalStatsReport stats-titles]])])

(defn ReviewCard [{:keys [href img img-alt header description extra] :as _card}]
  [:div.ui.raised.card (when href {:on-click #(nav/nav href)
                                   :style {:cursor "pointer"}})
   (when img [:div.image [:img {:src img :alt img-alt}]])
   [:div.content
    (when header       [:a.header (when href {:href href}) header])
    (when href         [:div.meta [UrlLink href]])
    (when description  [:div.description description])]
   (when (seq extra)
     [:div.extra.content
      (doall (for [[i x] (map-indexed vector extra)]
               ^{:key i} [:span x (when-not (= (inc i) (count extra))
                                    [:br])]))])])

(defn FeaturedReviews [{:keys [header-1 header-2 header-3 cards]}]
  [:div.ui.segment.center.aligned {:style {:border 0 :border-radius 0 :padding-top 60
                                           :margin-top 0 :margin-bottom 0}}
   [:div.description.wrapper.open-sans
    {:style {:margin "auto" :max-width "600px" :padding-bottom 0}}
    (when header-1 [:h1 {:style {:margin-top 5 :font-size "48px"}} header-1])
    (when header-2 [:h2 header-2])]
   (when header-3 [:h3 {:style {:margin-bottom 0}} header-3])
   (when (seq cards)
     [:div.ui.stackable.cards
      {:style {:max-width "1000px" :margin "auto"}
       :class (css [(= 2 (count cards)) "two"
                    (= 3 (count cards)) "three"])}
      (doall (for [[i card] (map-indexed vector cards)]
               ^{:key i} card))])])
