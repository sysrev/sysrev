(ns sysrev.label.define
  (:require [bouncer.validators :as v]
            [clojure.string :as str]
            [clojure.set :as set]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.label.core :as label]
            [honeysql.helpers :as sqlh :refer [select from where]]))

;; label validations

(defn used-label?
  "Has a label been set for an article?"
  [label-id]
  (cond
    ;; string value implies label is not yet created
    (string? label-id) false

    (uuid? label-id)
    (boolean (> (count (-> (select :article-id)
                           (from :article-label)
                           (where [:= :label-id label-id])
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
  [label-id all-values]
  (cond
    ;; label-id a string, the label has not been saved yet
    (string? label-id)
    true
    ;; the label hasn't been used yet
    (not (used-label? label-id))
    true
    ;; the label has been used
    (used-label? label-id)
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

(defn categorical-definition-validations
  [definition label-id]
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
    [(partial only-deleteable-all-values-removed? label-id)
     :message
     (str "An option can not be removed from a category if the label has already been set for an article. "
          "The options for this label were originally "
          (when-not (string? label-id)
            (str/join "," (get-in (label/get-label label-id) [:definition :all-values]))))]]

   :inclusion-values
   [[sequential?
     :message "[Error] Inclusion Values is non-sequential"]
    [v/every string?
     :message "[Error] Invalid value for \"Inclusion Values\""]
    [v/every #(contains? (set (:all-values definition)) %)
     :message "Inclusion values must each be present in list of categories"]]})

(defn label-validations
  "Given a label, return a validation map for it"
  [{:keys [value-type required definition label-id]}]
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

   :question [[v/required
               :message "Question text must be provided"]
              [v/string
               :message "[Error] Invalid value for \"Question\""]]

   :short-label [[v/required
                  :message "Display name must be provided"]
                 [v/string
                  :message "[Error] Invalid value for \"Display Name\""]]

   :required [[boolean-or-nil?
               :message "[Error] Invalid value for \"Required\""]]

   :consensus [#_ [#(not (and (true? %) (false? required)))
                   :message "Answer must be required when requiring consensus"]]

   ;; each value-type has a different definition
   :definition (condp = value-type
                 "boolean" boolean-definition-validations
                 "string" string-definition-validations
                 "categorical" (categorical-definition-validations definition label-id)
                 {})})
