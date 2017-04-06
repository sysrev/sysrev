(ns sysrev.ui.summary
  (:require [reagent.core :as r]
            [sysrev.util :refer [nav]]
            [sysrev.state.core :refer [data current-project-id]]
            [sysrev.base :refer [st]]
            [sysrev.state.project :refer [project]]
            [sysrev.ui.components :refer [selection-dropdown]]
            [sysrev.routes :as routes]))

(def fake-data [{:title "Some title here" :status "Some status" :key "123123"}])

(defonce summary-filter (r/atom {:conflicts-only false
                                 :selected-key nil
                                 :search-value ""
                                 :page 0}))


(defprotocol Conflictable
  (is-resolved? [this])
  (is-concordance? [this])
  (is-discordance? [this])
  (is-single? [this])
  (resolution [this]))

(defprotocol IdGroupable
  (id-grouped [this]))

(defprotocol AnswerGroupable
  (label-grouped [this]))

(defprotocol Groupable
  (num-groups [this]))

(defrecord ArticleLabel [article-id primary-title user-id answer])

(defrecord LabelGrouping [answer articles]
  Groupable
  (num-groups [this] (count articles)))

(defrecord IdGrouping [article-id article-labels]
  Groupable
  (num-groups [this] (count article-labels))
  AnswerGroupable
  (label-grouped [this] (mapv (fn [[k v]] (->LabelGrouping k v)) (group-by :answer article-labels)))
  Conflictable
  (is-resolved? [this] (->> article-labels (mapv :resolves) (filterv identity) first))
  (resolution [this] (->> article-labels (filterv :resolves) first))
  (is-discordance? [this] (-> (label-grouped this) (keys) (count) (> 1)))
  (is-single? [this] (= 1 (num-groups this)))
  (is-concordance? [this] (-> (label-grouped this) (count) (= 1) (and (not (is-single? this))))))

(defrecord ArticleLabels [articles]
  IdGroupable
  (id-grouped [this] (mapv (fn [[k v]] (->IdGrouping k v)) (group-by :article-id articles))))

(def handlers {:conflicts-only #(swap! summary-filter update :conflicts-only not)
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


(defn label-selector [labels selected-key]
  (letfn [(select-key [key] (nav (routes/summary {:label-id key})))
          (is-selected? [key] (= selected-key key))]
    (let [selected-label (->> labels (filter (comp is-selected? :label-id)) first)]
      [selection-dropdown
       [:div.text (:name selected-label)]
       (->> labels
            (mapv
              (fn [{:keys [label-id name]}]
                [:div.item
                 (into {:key label-id :on-click #(select-key label-id)}
                   (when (is-selected? label-id)
                     {:class "active selected"}))
                 name])))])))

(defn summary-filter-view [state handlers]
  (let [search-value (:search-value state)
        search-update (:search-update handlers)]
    [:div
     [:div.ui.dividing.header "Filter"]
     [search-box search-value search-update]
     [:div.ui.field
      [label-selector (vals (project :labels)) (st :page :summary :label-id)]]
     [:div.ui.form>div.field
      [:label "Conflicts only"]
      [:input.ui.checkbox {:type "checkbox"
                           :checked (when (:conflicts-only state) "checked")
                           :on-click (:conflicts-only handlers)}]]]))


(defn answer-cell [label-groups]
  (cond
    (is-resolved? label-groups) [:td (resolution label-groups)]
    (is-concordance? label-groups) [:td "Concordance"]
    (is-single? label-groups) [:td "Single"]
    :else [:td "Discordance"]))

(defn summary [id-grouped-articles row-select]
  [:table.ui.celled.fluid.table
   [:thead>tr
    [:th "Title"]
    [:th "Answer"]]
   [:tbody
    (->> (take 30 id-grouped-articles)
         (map
           (fn [las]
             (let [fla (first (:article-labels las))
                   key (:article-id las)
                   title (:primary-title fla)]
                [:tr {:on-click #(row-select key) :key key :class (when (is-selected? key) "active")}
                 [:td>p title]
                 [answer-cell las]])))
        (doall))]])

(defn summary-page []
  (let [label-id (st :page :summary :label-id)
        label-activity (data :label-activity)
        labeled-articles (->ArticleLabels (mapv map->ArticleLabel (get label-activity label-id)))
        grouped-articles (id-grouped labeled-articles)
        filtered-articles
          (if (:conflicts-only @summary-filter)
            (filterv is-discordance? grouped-articles)
            grouped-articles)]
    [:div
     [:div.ui.segment
      [summary-filter-view @summary-filter handlers]]
     [summary filtered-articles select-key]
     [:div.ui.segment
      (when (:conflicts-only @summary-filter)
        [:p "Showing only conflicts"])
      [:pre
       (with-out-str
         (cljs.pprint/pprint (->> filtered-articles first)))]]]))
