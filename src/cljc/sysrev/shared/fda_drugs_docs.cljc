(ns sysrev.shared.fda-drugs-docs
  (:require [clojure.string :as str]
            [medley.core :as medley]))

(defn not-blank
  "Returns s if s is non-blank, else nil."
  [^String s]
  (when-not (or (nil? s) (str/blank? s)) s))

(defn canonicalize-search-string [^String s]
  (when (not-blank s)
    (str/trim (str/lower-case s))))

(defn canonicalize-query [{:keys [search]}]
  {:search (canonicalize-search-string search)})

(defn query->datapub-input [query]
  (let [{:keys [search]} (canonicalize-query query)]
    {:datasetId 2
     :uniqueExternalIds true
     :query
     (->> {:type "AND"
           :text [(when search
                    {:search search
                     :useEveryIndex true})]}
          (medley/map-vals
           (fn [v]
             (if (string? v)
               v
               (remove empty? v))))
          (medley/filter-vals seq))}))
