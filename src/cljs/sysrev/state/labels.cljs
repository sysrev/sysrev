(ns sysrev.state.labels
  (:require [clojure.string :as str]
            [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.shared.util :refer [in?]]))

(reg-sub
 ::labels
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]] (:labels project)))

(defn project-labels [db & [project-id]]
  (let [project-id (or project-id (active-project-id db))
        project (get-project-raw db project-id)]
    (:labels project)))

(defn get-label-raw [db label-id & [project-id]]
  (get (project-labels db project-id) label-id))

(reg-sub
 ::label
 (fn [[_ label-id project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels] [_ label-id]]
   (get labels label-id)))

(defn- label-ordering-key
  "Sort key function for project label entries. Used to order labels for
  display in label editor, and for all other purposes where labels are
  displayed sequentially."
  [{:keys [required category value-type project-ordering name]}]
  (let [inclusion? (= category "inclusion criteria")
        extra? (= category "extra")
        boolean? (= value-type "boolean")
        categorical? (= value-type "categorical")]
    [(cond
       (= name "overall include") -20
       (and required inclusion? boolean?) -10
       (and required inclusion?) -9
       (and required boolean?) -8
       required -7
       (and inclusion? boolean?) 0
       (and inclusion? categorical?) 1
       inclusion? 2
       (and extra? boolean?) 3
       (and extra? categorical?) 4
       extra? 5
       :else 6)
     project-ordering]))

(defn- alpha-label-ordering-key
  "Sort key function for project label entries. Prioritize alphabetical
   sorting for large numbers of labels."
  [{:keys [required short-label value-type]}]
  (let [required (if required 0 1)
        value-type (case value-type
                     "boolean" 0
                     "categorical" 1
                     "string" 2
                     3)
        string (str/lower-case short-label)]
    [required string value-type]))

(defn sort-project-labels [labels & [include-disabled?]]
  (->> (vals labels)
       (filter #(or include-disabled? (:enabled %)))
       (#(if true #_ (>= (count %) 15)
           (sort-by alpha-label-ordering-key < %)
           (sort-by label-ordering-key %)))
       (mapv :label-id)))

(defn project-label-ids [db & [project-id include-disabled?]]
  (sort-project-labels (project-labels db project-id)
                       include-disabled?))

;; Use this to get a sequence of label-id in project, in a consistent
;; sorted order.
(reg-sub
 :project/label-ids
 (fn [[_ project-id include-disabled?]]
   [(subscribe [::labels project-id])])
 (fn [[labels] [_ _ include-disabled?]]
   (sort-project-labels labels include-disabled?)))

(reg-sub
 :project/have-label?
 (fn [[_ label-id project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels] [_ label-id]]
   (if (contains? labels label-id) true nil)))

(reg-sub
 :project/overall-label-id
 (fn [[_ project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels]]
   (->> (keys labels)
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

(reg-sub
 :label/from-local-id
 (fn [db [_ project-id]]
   (from-label-local-id db project-id)))

(reg-sub
 :label/id-from-short-label
 (fn [[_ short-label project-id]]
   [(subscribe [::labels project-id])])
 (fn [[labels] [_ short-label]]
   (->> (vals labels)
        (filter #(= (:short-label %) short-label))
        first
        :label-id)))

(reg-sub
 :label/required?
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:required label)))

(reg-sub
 :label/name
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:name label)))

(reg-sub
 :label/overall-include?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/name label-id project-id])])
 (fn [[name]] (= name "overall include")))

(reg-sub
 :label/display
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (or (:short-label label) (:name label))))

(reg-sub
 ::definition
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:definition label)))

(reg-sub
 :label/question
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:question label)))

(reg-sub
 :label/value-type
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:value-type label)))
;;
(reg-sub
 :label/boolean?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])])
 (fn [[value-type]] (= value-type "boolean")))
;;
(reg-sub
 :label/categorical?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])])
 (fn [[value-type]] (= value-type "categorical")))
;;
(reg-sub
 :label/string?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])])
 (fn [[value-type]] (= value-type "string")))

(reg-sub
 :label/category
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:category label)))

(reg-sub
 :label/inclusion-criteria?
 (fn [[_ label-id project-id]]
   [(subscribe [:label/category label-id project-id])])
 (fn [[category]] (= category "inclusion criteria")))

(reg-sub
 :label/all-values
 (fn [[_ label-id project-id]]
   [(subscribe [:label/value-type label-id project-id])
    (subscribe [::definition label-id project-id])])
 (fn [[value-type definition]]
   (case value-type
     "boolean" [true false]
     "categorical" (:all-values definition)
     nil)))

(reg-sub
 :label/inclusion-values
 (fn [[_ label-id project-id]]
   [(subscribe [::definition label-id project-id])])
 (fn [[definition]] (:inclusion-values definition)))

(reg-sub
 :label/examples
 (fn [[_ label-id project-id]]
   [(subscribe [::definition label-id project-id])])
 (fn [[definition]] (:examples definition)))

(reg-sub
 :label/enabled?
 (fn [[_ label-id project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label]] (:enabled label)))

(reg-sub
 :label/multi?
 (fn [[_ label-id project-id]]
   [(subscribe [::definition label-id project-id])])
 (fn [[definition]] (boolean (:multi? definition))))

(reg-sub
 :label/valid-string-value?
 (fn [[_ label-id val project-id]]
   [(subscribe [:label/string? label-id project-id])
    (subscribe [::definition label-id project-id])])
 (fn [[string-label? definition] [_ label-id val _]]
   (when string-label?
     (let [{:keys [regex max-length]} definition]
       (boolean
        (and
         (string? val)
         (<= (count val) max-length)
         (or (empty? regex)
             (some #(re-matches (re-pattern %) val) regex))))))))

(reg-sub
 :label/non-empty-answer?
 (fn [[_ label-id answer project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label] [_ label-id answer project-id]]
   (let [{:keys [value-type]} label]
     (case value-type
       "boolean"      (boolean? answer)
       "categorical"  (not-empty answer)
       "string"       (not-empty (remove (comp empty? str/trim) answer))
       nil))))

(reg-sub
 :label/answer-inclusion
 (fn [[_ label-id _ project-id]]
   [(subscribe [::label label-id project-id])])
 (fn [[label] [_ _ answer _]]
   (let [{:keys [definition value-type]} label
         ivals (:inclusion-values definition)]
     (case value-type
       "boolean"
       (cond
         (empty? ivals) nil
         (nil? answer) nil
         :else (boolean (in? ivals answer)))
       "categorical"
       (cond
         (empty? ivals) nil
         (nil? answer) nil
         (empty? answer) nil
         :else (boolean (some (in? ivals) answer)))
       nil))))

(defn real-answer? [answer]
  (if (or (nil? answer)
          ((every-pred coll? empty?) answer))
    false true))
