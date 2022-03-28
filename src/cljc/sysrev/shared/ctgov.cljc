(ns sysrev.shared.ctgov
  (:require [clojure.string :as str]
            [medley.core :as medley]))

(def gender-options
  [["all" "All"]
   ["female" "Female"]
   ["male" "Male"]])

(def gender-options-map (into {} gender-options))

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

(def sponsor-class-options
  [["NIH" "NIH"]
   ["FED" "Other U.S. Federal Agency"]
   ["INDUSTRY" "Industry"]
   ["INDIV" "Individual"]
   ["NETWORK" "Network"]
   ["OTHER" "Other"]
   ["OTHER_GOV" "Other Government"]
   ["AMBIG" "Ambiguous"]
   ["UNKNOWN" "Unknown"]])

(def study-type-options
  [["observational" "Observational"]
   ["expanded-access" "Expanded Access"]
   ["interventional" "Interventional"]])

(def study-type-options-map (into {} study-type-options))

(defn ensure-set [x]
  (cond
    (nil? x) #{}
    (set? x) x
    (sequential? x) (set x)
    :else (ex-info "Argument must be nil, an IPersistentSet, or a Sequential."
                   {:x x})))

(defn not-blank
  "Returns s if s is non-blank, else nil."
  [^String s]
  (when-not (or (nil? s) (str/blank? s)) s))

(defn canonicalize-search-string [^String s]
  (when (not-blank s)
    (str/trim (str/lower-case s))))

(defn canonicalize-query [{:keys [filters search]}]
  {:filters (-> filters
                (update :condition canonicalize-search-string)
                (update :countries (comp not-empty ensure-set))
                (update :gender (comp not-empty ensure-set))
                (update :intervention canonicalize-search-string)
                (update :recruitment (comp not-empty ensure-set))
                (update :sponsor-class (comp not-empty ensure-set))
                (update :study-type (comp not-empty ensure-set))
                (->> (medley/remove-vals nil?) not-empty))
   :search (canonicalize-search-string search)})

(defn string-set-filters [st f path]
  (when (seq st)
    {:type "OR"
     :string
     (mapv
      #(do {:eq (f %)
            :path path})
      st)}))

(defn query->datapub-input [query]
  (let [{:keys [filters search]} (canonicalize-query query)
        {:keys [condition countries gender intervention
                recruitment sponsor-class study-type]}
        #__ filters]
    {:datasetId "1"
     :uniqueExternalIds true
     :query
     (->> {:type "AND"
           :query [(string-set-filters
                    countries identity
                    (pr-str ["ProtocolSection" "ContactsLocationsModule"
                             "LocationList" "Location" :* "LocationCountry"]))
                   (string-set-filters
                    gender gender-options-map
                    (pr-str ["ProtocolSection" "EligibilityModule" "Gender"]))
                   (string-set-filters
                    recruitment recruitment-options-map
                    (pr-str ["ProtocolSection" "StatusModule" "OverallStatus"]))
                   (string-set-filters
                    sponsor-class identity
                    (pr-str ["ProtocolSection" "SponsorCollaboratorsModule"
                             "LeadSponsor" "LeadSponsorClass"]))
                   (string-set-filters
                    study-type study-type-options-map
                    (pr-str ["ProtocolSection" "DesignModule" "StudyType"]))]
           :text [(when condition
                    {:search condition
                     :paths [(pr-str ["ProtocolSection" "ConditionsModule"
                                      "ConditionList" "Condition" :*])]})
                  (when intervention
                    {:search intervention
                     :paths [(pr-str ["ProtocolSection"
                                      "ArmsInterventionsModule"
                                      "InterventionList" "Intervention" :*
                                      "InterventionName"])]})
                  (when search
                    {:search search
                     :useEveryIndex true})]}
          (medley/map-vals
           (fn [v]
             (if (string? v)
               v
               (remove empty? v))))
          (medley/filter-vals seq))}))
