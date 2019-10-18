(ns sysrev.shared.labels
  (:require [clojure.string :as str]))

(defn cleanup-label-answer [label answer]
  (case (:value-type label)
    "string" (->> answer (mapv str/trim) (filterv not-empty))
    answer))

(defn- alpha-label-ordering-key
  "Sort key function for project label entries. Prioritize alphabetical
   sorting for large numbers of labels."
  [{:keys [name required short-label value-type]}]
  (let [overall (= name "overall include")
        required (cond overall 0 required 1 :else 2)
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
       (sort-by alpha-label-ordering-key)
       (mapv :label-id)))
