(ns sysrev.state.label
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe reg-sub]]
            [medley.core :refer [find-first]]
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
         (fn [[_ _ _ project-id]] (subscribe [::labels project-id]))
         (fn [labels [_ root-label-id label-id _]]
           (if (contains? #{nil "na"} root-label-id)
             (get labels label-id)
             (get-in labels [root-label-id :labels label-id]))))

(defn project-overall-label-id [db & [project-id]]
  (:label-id (find-first #(= "overall include" (:name %))
                         (vals (project-labels db project-id)))))

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

;; Use this to get a sequence of label-id from project in a consistent
;; sorted order.
(reg-sub :project/label-ids
         (fn [[_ project-id _]] (subscribe [::labels project-id]))
         (fn [labels [_ _ include-disabled?]]
           (sort-client-project-labels labels include-disabled?)))

(reg-sub :project/overall-label-id
         (fn [[_ project-id]] (subscribe [::labels project-id]))
         (fn [labels]
           (->> (keys labels)
                (find-first #(= "overall include" (get-in labels [% :name]))))))

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
           (:label-id (find-first #(= short-label (:short-label %))
                                  (vals labels)))))

(reg-sub :label/required?
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:required %))

(reg-sub :label/name
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:name %))

(reg-sub :label/display
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(or (:short-label %) (:name %)))

(reg-sub ::definition
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:definition %))

(reg-sub :label/question
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:question %))

(reg-sub :label/value-type
         (fn [[_ root-label-id label-id _project-id]]
           (subscribe [::label root-label-id label-id]))
         #(:value-type %))

(reg-sub :label/category
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:category %))

(reg-sub :label/labels
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:labels %))

(reg-sub :label/inclusion-criteria?
         (fn [[_ root-label-id label-id project-id]] (subscribe [:label/category root-label-id label-id project-id]))
         (fn [category] (= category "inclusion criteria")))

(reg-sub :label/all-values
         (fn [[_ root-label-id label-id project-id]]
           [(subscribe [:label/value-type root-label-id label-id project-id])
            (subscribe [::definition root-label-id label-id project-id])])
         (fn [[value-type definition]]
           (case value-type
             "boolean" [true false]
             "categorical" (:all-values definition)
             nil)))

(reg-sub :label/inclusion-values
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::definition root-label-id label-id project-id]))
         #(:inclusion-values %))

(reg-sub :label/examples
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::definition root-label-id label-id project-id]))
         #(:examples %))

(reg-sub :label/enabled?
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         #(:enabled %))

(reg-sub :label/multi?
         (fn [[_ root-label-id label-id project-id]]
           (subscribe [::definition root-label-id label-id project-id]))
         #(boolean (:multi? %)))

(reg-sub :label/valid-string-value?
         (fn [[_ root-label-id label-id _val project-id]]
           [(subscribe [:label/value-type root-label-id label-id project-id])
            (subscribe [::definition root-label-id label-id project-id])])
         (fn [[value-type definition] [_ _ _  val _]]
           (when (= value-type "string")
             (let [{:keys [regex max-length]} definition]
               (boolean (and (string? val)
                             (<= (count val) max-length)
                             (or (empty? regex)
                                 (some #(re-matches (re-pattern %) val) regex))))))))

(reg-sub :label/non-empty-answer?
         (fn [[_ root-label-id label-id _answer project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         (fn [label [_ _ _ answer _]]
           (let [{:keys [value-type]} label]
             (case value-type
               "boolean"      (boolean? answer)
               "categorical"  (not-empty answer)
               "string"       (not-empty (remove (comp empty? str/trim) answer))
               nil))))

(reg-sub :label/answer-inclusion
         (fn [[_ root-label-id label-id _answer project-id]]
           (subscribe [::label root-label-id label-id project-id]))
         (fn [label [_ _ _ answer _]]
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
