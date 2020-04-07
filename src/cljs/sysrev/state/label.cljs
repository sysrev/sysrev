(ns sysrev.state.label
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.util :refer [in?]]))

(reg-sub ::labels
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:labels %))

(defn project-labels [db & [project-id]]
  (when-let [project-id (or project-id (active-project-id db))]
    (:labels (get-project-raw db project-id))))

(defn get-label-raw [db label-id & [project-id]]
  (get (project-labels db project-id) label-id))

(reg-sub ::label
         (fn [[_ _ project-id]] (subscribe [::labels project-id]))
         (fn [labels [_ label-id _]] (get labels label-id)))

(defn project-overall-label-id [db & [project-id]]
  (->> (vals (project-labels db project-id))
       (filter #(= (:name %) "overall include"))
       first :label-id))

(reg-sub :project/labels-raw
         (fn [db [_ project-id]] (project-labels db project-id)))

(defn sort-client-project-labels
  "Returns sorted label ids using `:project-ordering` values, while
  handling disabled labels which have no ordering value."
  [labels include-disabled?]
  (->> (vals labels)
       (filter #(or include-disabled? (:enabled %)))
       (sort-by #(or (:short-label %) ""))
       (sort-by :project-ordering <)
       (mapv :label-id)))

(defn project-label-ids [db & [project-id include-disabled?]]
  (-> (project-labels db project-id)
      (sort-client-project-labels include-disabled?)))

;; Use this to get a sequence of label-id from project in a consistent
;; sorted order.
(reg-sub :project/label-ids
         (fn [[_ project-id _]] (subscribe [::labels project-id]))
         (fn [labels [_ _ include-disabled?]]
           (sort-client-project-labels labels include-disabled?)))

(reg-sub :project/overall-label-id
         (fn [[_ project-id]] (subscribe [::labels project-id]))
         (fn [labels] (->> (keys labels)
                           (filter (fn [label-id]
                                     (let [{:keys [name]} (get labels label-id)]
                                       (= name "overall include"))))
                           first)))

(defn from-label-local-id [db & [project-id]]
  (let [labels (project-labels db project-id)]
    (->> (vals labels)
         (map (fn [{:keys [label-id label-id-local]}]
                [label-id-local label-id]))
         (apply concat)
         (apply hash-map))))

(reg-sub :label/from-local-id
         (fn [db [_ project-id]]
           (from-label-local-id db project-id)))

(reg-sub :label/id-from-short-label
         (fn [[_ _ project-id]] (subscribe [::labels project-id]))
         (fn [labels [_ short-label _]]
           (:label-id (->> (vals labels) (filter #(= (:short-label %) short-label)) first))))

(reg-sub :label/required?
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:required label)))

(reg-sub :label/name
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:name label)))

(reg-sub :label/display
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (or (:short-label label) (:name label))))

(reg-sub ::definition
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:definition label)))

(reg-sub :label/question
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:question label)))

(reg-sub :label/value-type
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:value-type label)))

(reg-sub :label/category
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:category label)))

(reg-sub :label/inclusion-criteria?
         (fn [[_ label-id project-id]] (subscribe [:label/category label-id project-id]))
         (fn [category] (= category "inclusion criteria")))

(reg-sub :label/all-values
         (fn [[_ label-id project-id]]
           [(subscribe [:label/value-type label-id project-id])
            (subscribe [::definition label-id project-id])])
         (fn [[value-type definition]]
           (case value-type
             "boolean" [true false]
             "categorical" (:all-values definition)
             nil)))

(reg-sub :label/inclusion-values
         (fn [[_ label-id project-id]] (subscribe [::definition label-id project-id]))
         (fn [definition] (:inclusion-values definition)))

(reg-sub :label/examples
         (fn [[_ label-id project-id]] (subscribe [::definition label-id project-id]))
         (fn [definition] (:examples definition)))

(reg-sub :label/enabled?
         (fn [[_ label-id project-id]] (subscribe [::label label-id project-id]))
         (fn [label] (:enabled label)))

(reg-sub :label/multi?
         (fn [[_ label-id project-id]] (subscribe [::definition label-id project-id]))
         (fn [definition] (boolean (:multi? definition))))

(reg-sub :label/valid-string-value?
         (fn [[_ label-id _ project-id]]
           [(subscribe [:label/value-type label-id project-id])
            (subscribe [::definition label-id project-id])])
         (fn [[value-type definition] [_ _ val _]]
           (when (= value-type "string")
             (let [{:keys [regex max-length]} definition]
               (boolean (and (string? val)
                             (<= (count val) max-length)
                             (or (empty? regex)
                                 (some #(re-matches (re-pattern %) val) regex))))))))

(reg-sub :label/non-empty-answer?
         (fn [[_ label-id _ project-id]] (subscribe [::label label-id project-id]))
         (fn [label [_ _ answer _]]
           (let [{:keys [value-type]} label]
             (case value-type
               "boolean"      (boolean? answer)
               "categorical"  (not-empty answer)
               "string"       (not-empty (remove (comp empty? str/trim) answer))
               nil))))

(reg-sub :label/answer-inclusion
         (fn [[_ label-id _ project-id]] (subscribe [::label label-id project-id]))
         (fn [label [_ _ answer _]]
           (let [{:keys [definition value-type]} label
                 ivals (:inclusion-values definition)]
             (case value-type
               "boolean"      (if (or (empty? ivals) (nil? answer))
                                nil (boolean (in? ivals answer)))
               "categorical"  (if (or (empty? ivals) (nil? answer) (empty? answer))
                                nil (boolean (some (in? ivals) answer)))
               nil))))

(defn real-answer? [answer]
  (if (or (nil? answer)
          ((every-pred coll? empty?) answer))
    false true))
