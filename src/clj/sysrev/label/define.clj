(ns sysrev.label.define
  (:require
   [bouncer.core :as b]
   [bouncer.validators :as v]
   [clojure.set :as set]
   [clojure.string :as str]
   [honeysql.helpers :as sqlh :refer [from select where]]
   [sysrev.db.core :as db :refer [do-query]]
   [sysrev.db.queries :as q]
   [sysrev.label.core :as label]))

;; label validations

(defn used-label?
  "Has a label been set for an article?"
  [label-id global-label-id]
  (cond
    ;; string value implies label is not yet created
    (string? label-id) false

    (uuid? label-id)
    (boolean (> (count (-> (select :article-id)
                           (from :article-label)
                           (where [:or
                                   [:= :label-id label-id]
                                   [:= :label-id global-label-id]])
                           (do-query)))
                0))

    :else
    (throw (Exception. "used-label? - invalid label-id value"))))

(defn boolean-or-nil?
  "Is the value supplied boolean or nil?"
  [value]
  (or (boolean? value)
      (nil? value)))

(defn every-boolean-or-nil?
  "Is every value boolean or nil?"
  [value]
  (and (seqable? value)
       (every? boolean-or-nil? value)))

(defn only-deleteable-all-values-removed?
  "If the label-id is a string (i.e. it doesn't yet exist on the
  server), the label hasn't been set for an article, or all-values has
  not had entries deleted if the label does exist, return
  true. Otherwise, return false"
  [label-id global-label-id all-values]
  (cond
    ;; label-id a string, the label has not been saved yet
    (string? label-id)
    true
    ;; the label hasn't been used yet
    (not (used-label? label-id global-label-id))
    true
    ;; the label has been used
    (used-label? label-id global-label-id)
    ;; ... so determine if a category has been deleted
    (set/superset? (set all-values)
                   ;; sometimes the user inadvertently includes a space
                   (set (filter #(not (clojure.string/blank? %))
                                (get-in (label/get-label label-id) [:definition :all-values]))))))

(def boolean-definition-validations
  {:inclusion-values
   [[every-boolean-or-nil?
     :message "[Error] Invalid value for \"Inclusion Values\""]]})

(def string-definition-validations
  {:multi?     [[boolean-or-nil?
                 :message "[Error] Invalid value for \"Multiple Values\""]]

   :examples   [[v/every string?
                 :message "[Error] Invalid value for \"Examples\""]]

   :max-length [[v/required
                 :message "Max length must be provided"]
                [v/integer
                 :message "Max length must be an integer"]]

   :entity     [[v/string
                 :message "[Error] Invalid value for \"Entity\""]]})

(def relationship-definition-validations
  {})

(defn categorical-definition-validations
  [definition label-id global-label-id]
  {:multi?
   [[boolean-or-nil?
     :message "Allow multiple values must be true, false or nil"]]

   :all-values
   [[v/required
     :message "Category options must be provided"]
    [sequential?
     :message "[Error] Categories value is non-sequential"]
    [v/every string?
     :message "[Error] Invalid value for \"Categories\""]
    [(partial only-deleteable-all-values-removed? label-id global-label-id)
     :message
     (str "An option can not be removed from a category if the label has already been set for an article. "
          "The options for this label were originally "
          (when-not (string? label-id)
            (str/join "," (get-in (label/get-label label-id) [:definition :all-values]))))]]
   :default-value
   [[#(or (not %) (sequential? %)) ; optional val
     :message "[Error] Categories value is non-sequential"]
    [v/every #(contains? (set (:all-values definition)) %)
     :message "Default values must each be present in list of categories"]]
   :inclusion-values
   [[sequential?
     :message "[Error] Inclusion Values is non-sequential"]
    [v/every string?
     :message "[Error] Invalid value for \"Inclusion Values\""]
    [v/every #(contains? (set (:all-values definition)) %)
     :message "Inclusion values must each be present in list of categories"]]})

(defn short-label-unique? [short-label {:keys [label-id project-id root-label-id-local] :as label}]
  (if-let [group-label (::group-label label)]
    (-> group-label :labels vals
        (->> (map (comp str/trim str/lower-case :short-label)))
        frequencies
        (get (str/trim (str/lower-case short-label)))
        (#(< (or % 0) 2)))
    (-> (q/find :label {} :label-id
                :where [:and
                        (when (uuid? label-id)
                          [:not= :label-id label-id])
                        [:= :project-id project-id]
                        [:= :root-label-id-local root-label-id-local]
                        [:=
                         [:trim [:lower :short-label]]
                         [:trim [:lower short-label]]]])
        some? not)))

(defn label-validations
  "Given a label, return a validation map for it"
  [{:keys [value-type required definition label-id global-label-id]
    :as label}]
  ; (println value-type)
  (println "---------")
  (println definition)
  {:value-type
   [[v/required
     :message "[Error] Label type is not set"]
    [v/string
     :message "[Error] Invalid value for label type (non-string)"]
    [(partial contains? (set label/valid-label-value-types))
     :message "[Error] Invalid value for label type (option not valid)"]]

   :project-id [[v/required
                 :message "[Error] Project ID not set"]
                [v/integer
                 :message "[Error] Project ID is not integer"]]

   ;; these are going to be generated by the client so shouldn't
   ;; be blank, checking anyway
   :name [[v/required
           :message "Label name must be provided"]
          [v/string
           :message "[Error] Invalid value for \"Label Name\""]]
   :question
   (if-not (= value-type "group")
     [[v/required
       :message "Question text must be provided"]
      [v/string
       :message "[Error] Invalid value for \"Question\""]]
     [[(constantly true)
       :message "Should not see this error"]])

   :short-label [[v/required
                  :message "Display name must be provided"]
                 [v/string
                  :message "[Error] Invalid value for \"Display Name\""]
                 [#(short-label-unique? % label)
                  :message "There is already a label with this name"]]

   :required [[boolean-or-nil?
               :message "[Error] Invalid value for \"Required\""]]

   ;; each value-type has a different definition
   :definition (condp = value-type
                 "boolean" boolean-definition-validations
                 "string" string-definition-validations
                 "categorical" (categorical-definition-validations definition label-id global-label-id)
                 "group" [[label-validations]]
                 "relationship" relationship-definition-validations
                 {})})

(defn split-labels-set
  "Given a map of labels, split them into a set of group-labels and regular-labels
  as they must be treated differently"
  [m]
  (let [all-labels (set (vals m))
        group-labels (set (filter #(= "group" (:value-type %)) all-labels))
        regular-labels (set/difference all-labels group-labels)]
    {:group-labels group-labels
     :regular-labels regular-labels}))

(defn regular-labels-valid?
  [coll]
  (->> coll
       (map #(b/valid? % (label-validations %)))
       (every? true?)))

(defn group-label-valid?
  [m]
  (and (b/valid? m (label-validations m))
       (not (nil? (-> m :labels)))
       (->> m :labels vals
            (map #(assoc % ::group-label m))
            regular-labels-valid?)))

(defn group-labels-valid?
  [coll]
  (->> coll
       (map group-label-valid?)
       (every? true?)))

(defn all-labels-valid?
  "Are all of the labels in m valid?"
  [m]
  (let [{:keys [regular-labels group-labels]} (split-labels-set m)
        regular-valid? (regular-labels-valid? regular-labels)
        group-valid? (group-labels-valid? group-labels)]
    (every? true? [regular-valid? group-valid?])))

(defn regular-label-validated
  [m]
  (->
   ;; validate the label
   (b/validate m (label-validations m))
   ;; get the label map with attached errors
   second
   ;; rename bouncer.core/errors -> errors
   (set/rename-keys {:bouncer.core/errors :errors})
   ;; create a new hash map of labels which include
   ;; errors
   (#(hash-map (:label-id %) %))))

(defn regular-labels-validated
  "Given a set of regular validated labels, validate them"
  [coll]
  (->> coll
       (map regular-label-validated)
       ;; finally, return a map
       (apply merge)))

(defn group-label-validated
  [m]
  (let [label-id (:label-id m)
        label-validation (regular-label-validated m)
        labels (->> m :labels vals (map #(assoc % ::group-label m)))
        labels-validation (regular-labels-validated labels)]
    (if (empty? labels)
      ;; note: because code in define_labels.cljs does a postwalk and looks for :labels
      ;; this must use :labels-error and NOT :labels to avoid this error
      (assoc-in label-validation [label-id :errors :labels-error] '("Group label must include at least one sub label"))
      (assoc-in label-validation [label-id :labels] labels-validation))))

(defn group-labels-validated
  [coll]
  (->> coll
       (map group-label-validated)
       (apply merge)))

(defn validated-labels
  "Validate the labels in m, include any errors"
  [m]
  (let [{:keys [regular-labels group-labels]} (split-labels-set m)
        validated-regular-labels (regular-labels-validated regular-labels)
        validated-group-labels (group-labels-validated group-labels)]
    (apply merge [validated-regular-labels validated-group-labels])))
