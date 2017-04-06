(ns sysrev.ui.summary
  (:require [reagent.core :as r]
            [sysrev.base :refer [st]]
            [sysrev.state.project :refer [project]]))


(def data [{:title "Some title here" :status "Some status" :key "123123"}])



(defonce summary-filter (r/atom {:conflicts-only false
                                 :selected-key nil
                                 :search-value ""}))

(def handlers {:conflicts-only #(swap! summary-filter update :checked-only not)
               :search-update #(swap! summary-filter assoc :search-value %)})

(defn selected-key [] (:selected-key @summary-filter))

(defn is-selected? [key] (= (selected-key) key))

(defn select-key [key]
  (if (is-selected? key)
    (swap! summary-filter dissoc :selected-key)
    (swap! summary-filter assoc :selected-key key)))


(defn search-box [current-value value-changed]
  (let [update-value #(-> % .-target .-value value-changed)]
    [:div.ui.field
     [:div.ui.left.icon.input
      [:input {:placeholder "Search..." :on-change update-value :value current-value}]
      [:i.search.icon]]]))


(defn summary-filter-view [state handlers]
  (let [search-value (:search-value state)
        search-update (:search-update handlers)]
    [:div
     [:div.ui.dividing.header "Filter"]
     [search-box search-value search-update]
     [:div.ui.form>div.field
      [:label "Conflicts only"]
      [:input.ui.checkbox {:type "checkbox"
                           :checked (when (:conflicts-only state) "checked")
                           :on-click (:conflicts-only handlers)}]]]))


(defn summary [display-articles row-select]
  (letfn [(filter-articles [] display-articles)]
    (fn []
      (let [filtered-articles (filter-articles)]
        [:table.ui.celled.fluid.table
         [:thead
          [:tr
           [:th "Title"]
           [:th "Status"]]]
         [:tbody
          (->> display-articles
              (map
                (fn [{:keys [key title status]}]
                  [:tr {:on-click #(row-select key) :key key :class (when (is-selected? key) "active")}
                   [:td title]
                   [:td status]]))
              (doall))]]))))

(defn summary-page []
  [:div
   [:div.ui.segment
    [summary-filter-view @summary-filter handlers]]
   [summary data select-key]
   [:div.ui.segment
    (when (:checked-only @summary-filter)
      [:p "Showing only conflicts"])
    [:pre
     (with-out-str
       (cljs.pprint/pprint (project)))]]])
