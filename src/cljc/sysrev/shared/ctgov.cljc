(ns sysrev.shared.ctgov
  (:require [clojure.string :as str]
            [medley.core :as medley]))

(def recruitment-options
  [["not-yet-recruiting" "Not yet recruiting"]
   ["recruiting" "Recruiting"]
   ["enrolling-by-invitation" "Enrolling by invitation"]
   ["active-not-recruiting" "Active, not recruiting"]
   ["suspended" "Suspended"]
   ["terminated" "Terminated"]
   ["completed" "Completed"]
   ["withdrawn" "Withdrawn"]
   ["unknown-status" "Unknown status"]])

(def recruitment-options-map (into {} recruitment-options))

(defn ensure-set [x]
  (cond
    (nil? x) #{}
    (set? x) x
    (sequential? x) (set x)
    :else (ex-info "Argument must be nil, an IPersistentSet, or a Sequential."
                   {:x x})))

(defn canonicalize-query [{:keys [filters search]}]
  {:filters (-> filters
                (update :recruitment (comp not-empty ensure-set))
                (->> (medley/remove-vals nil?) not-empty))
   :search (str/lower-case (str/trim (or search "")))})

(defn recruiting-filters [recruitment]
  (when (seq recruitment)
    {:type "OR"
     :string
     (mapv
      #(-> {:eq (recruitment-options-map %)
            :path "[\"ProtocolSection\" \"StatusModule\" \"OverallStatus\"]"})
      recruitment)}))

(defn query->datapub-input [query]
  (let [{:keys [filters search]} (canonicalize-query query)
        {:keys [recruitment]} filters]
    {:datasetId 1
     :uniqueExternalIds true
     :query
     (->> {:type "AND"
           :query [(recruiting-filters recruitment)]
           :text [{:search search
                   :useEveryIndex true}]}
          (medley/map-vals
           (fn [v]
             (if (string? v)
               v
               (remove empty? v))))
          (medley/filter-vals seq))}))
