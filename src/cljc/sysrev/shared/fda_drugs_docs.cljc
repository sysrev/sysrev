(ns sysrev.shared.fda-drugs-docs
  (:require [clojure.string :as str]
            [medley.core :as medley]))

(def application-type-options
  [["NDA" "NDA"]
   ["ANDA" "ANDA"]
   ["BLA" "BLA"]])

(def application-type-options-map (into {} application-type-options))

(def review-document-type-options
  [["chemistry review" "Chemistry"]
   ["medical review" "Medical"]
   ["microbiology review" "Microbiology"]
   ["pharmacology review" "Pharmacology"]
   ["propietary name review" "Proprietary Name"]
   ["risk assessment and risk mitigation review" "Risk"]
   ["statistical review" "Statistical"]
   ["summary review" "Summary"]])

(def review-document-type-options-map (into {} review-document-type-options))

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
                (update :application-type (comp not-empty ensure-set))
                (update :review-document-type (comp not-empty ensure-set))
                (->> (medley/remove-vals nil?) not-empty))
   :search (canonicalize-search-string search)})

(defn string-set-filters [st f path]
  (when (seq st)
    {:type "OR"
     :string
     (mapv
      #(-> {:eq (f %)
            :path path})
      st)}))

(defn query->datapub-input [query]
  (let [{:keys [filters search]} (canonicalize-query query)
        {:keys [application-type review-document-type]} filters]
    {:datasetId 3
     :uniqueExternalIds true
     :query
     (->> {:type "AND"
           :query [(string-set-filters
                    application-type application-type-options-map
                    (pr-str ["metadata" "ApplType"]))
                   (string-set-filters
                    review-document-type identity
                    (pr-str ["metadata" "ReviewDocumentType"]))]
           :text [(when search
                    {:search search
                     :useEveryIndex true})]}
          (medley/map-vals
           (fn [v]
             (if (string? v)
               v
               (remove empty? v))))
          (medley/filter-vals seq))}))
