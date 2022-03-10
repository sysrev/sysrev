(ns sysrev.shared.labels
  (:require [clojure.string :as str]))

(def predictable-label-types #{"boolean" "categorical" "annotation"})

(defn cleanup-label-answer [label answer]
  (case (:value-type label)
    "string" (->> answer (mapv str/trim) (filterv not-empty))
    answer))
