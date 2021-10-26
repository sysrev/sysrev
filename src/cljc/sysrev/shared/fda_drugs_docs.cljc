(ns sysrev.shared.fda-drugs-docs
  (:require [clojure.string :as str]
            [medley.core :as medley]))

(def application-type-options
  [["NDA" "NDA"]
   ["ANDA" "ANDA"]
   ["BLA" "BLA"]])

(def application-type-options-map (into {} application-type-options))

(def document-type-options
  [["Label" "Label"]
   ["Letter" "Letter"]
   ["Review" "Review"]])

(def document-type-options-map (into {} document-type-options))

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
                (update :active-ingredient canonicalize-search-string)
                (update :application-type (comp not-empty ensure-set))
                (update :document-type (comp not-empty ensure-set))
                (update :drug-name canonicalize-search-string)
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
        {:keys [active-ingredient application-type document-type
                drug-name review-document-type]} filters]
    {:datasetId 3
     :uniqueGroupingIds true
     :query
     (->> {:type "AND"
           :query [(string-set-filters
                    application-type application-type-options-map
                    (pr-str ["metadata" "ApplType"]))
                   (string-set-filters
                    document-type identity
                    (pr-str ["metadata" "ApplicationDocsDescription"]))
                   (string-set-filters
                    review-document-type identity
                    (pr-str ["metadata" "ReviewDocumentType"]))]
           :text [(when active-ingredient
                    {:search active-ingredient
                     :paths [(pr-str ["metadata" "Products" :* "ActiveIngredient"])]})
                  (when drug-name
                    {:search drug-name
                     :paths [(pr-str ["metadata" "Products" :* "DrugName"])]})
                  (when search
                    {:search search
                     :useEveryIndex true})]}
          (medley/map-vals
           (fn [v]
             (if (string? v)
               v
               (remove empty? v))))
          (medley/filter-vals seq))}))
