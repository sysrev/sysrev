(ns sysrev.views.components.relationship-builder
  (:require [sysrev.views.semantic :refer [Select]]))

(defn add-row [relationships]
  (swap! relationships conj {:from nil :to nil :value nil :key (-> @relationships count inc)}))

(defn filter-rows [rows key]
  (remove #(= (:key %) key) rows))

(defn remove-row [relationships key]
  (swap! relationships filter-rows key))

(defn generate-select-values [values]
  (map #(hash-map :text % :value %) values))

(defn update-relationship-val [value type key relationships]
  (let [is-new-relation? (= (count (filter #(= key (:key %)) @relationships)) 0)]
    (if is-new-relation?
      (swap! relationships conj {type value :key key})
      (reset! relationships (map #(if (= (:key %) key) (conj % {type value}) %) @relationships)))))

(defn RelationshipBuilder [entity-values relationships]
  (let [options (generate-select-values entity-values)]
   [:div.field.relationship-field-values
    [:label [:span "Entity Relationships"]]
    [:div
     [:p.ui.tiny.positive.button {:on-click #(add-row relationships)} "+"]
     (for [row @relationships]
       [:div.flex-between {:style {:padding "4px 0"} :key (:key row)}
        [:div
         [:label "From"]
         [:div
          [Select {:options options
                   :on-change (fn [_e ^js f] (update-relationship-val (.-value f) :from (:key row) relationships))
                   :size "tiny"
                   :value (:from row)
                   :placeholder "From"}]]]
        [:div
         [:label "To"]
         [:div
          [Select {:options options
                   :on-change (fn [_e ^js f] (update-relationship-val (.-value f) :to (:key row) relationships))
                   :size "tiny"
                   :value (:to row)
                   :placeholder "To"}]]]
        [:div
         [:label "Value"]
         [:input {:type "Text" :value (:value row) :on-change #(update-relationship-val (-> % .-target .-value) :value (:key row) relationships)}]]
        [:div.flex-center-children {:style {:height "70px"}}
         [:p.ui.tiny.negative.button {:on-click #(remove-row relationships (:key row)) :style {:height "30px"}} "-"]]])]]))
