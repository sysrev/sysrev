(ns sysrev.fda-drugs.core
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sysrev.file-util.interface :as file-util])
  (:import (java.net URL)
           (java.nio.file Path)))

;; Reference: https://www.fda.gov/drugs/drug-approvals-and-databases/drugsfda-data-files
;; ActionTypes_Lookup and SubmissionPropertyType are omitted.

(set! *warn-on-reflection* true)

(def data-url "https://www.fda.gov/media/89850/download")

(defn download-data! [^Path path]
  (with-open [input-stream (.openStream (URL. data-url))]
    (file-util/copy! input-stream path #{:replace-existing})))

(defn parse-data [^Path path]
  (->> path file-util/read-zip-entries
       (map
        (fn [[filename bytes]]
          (let [rows (-> bytes io/reader (csv/read-csv :separator \tab)
                         (->> (map (fn [row] (map str/trim row)))))
                header (map keyword (first rows))]
            [(keyword (subs filename 0 (- (count filename) 4)))
             (map #(zipmap header %) (rest rows))])))
       (into {})))

(defn te-codes [{:keys [TE]}]
  (->> TE
       (reduce
        (fn [m {:keys [ApplNo ProductNo MarketingStatusID TECode]}]
          (update m [ApplNo ProductNo MarketingStatusID]
                  #(conj (or % []) %2) TECode))
        {})))

(defn marketing-status-descriptions [{:keys [MarketingStatus_Lookup]}]
  (->> MarketingStatus_Lookup
       (map
        (fn [{:keys [MarketingStatusDescription MarketingStatusID]}]
          [MarketingStatusID MarketingStatusDescription]))
       (into {})))

(defn marketing-statuses [{:keys [MarketingStatus] :as data}]
  (let [descriptions (marketing-status-descriptions data)
        codes (te-codes data)]
    (->> MarketingStatus
         (reduce
          (fn [m {:keys [ApplNo MarketingStatusID ProductNo] :as doc}]
            (update m [ApplNo ProductNo] #(conj (or % []) %2)
                    (-> (dissoc doc :ApplNo :MarketingStatusID :ProductNo)
                        (assoc :Description
                               (descriptions MarketingStatusID)
                               :TECodes (codes [ApplNo ProductNo MarketingStatusID])))))
          {}))))

(defn products [{:keys [Products] :as data}]
  (let [statuses (marketing-statuses data)]
    (->> Products
         (reduce
          (fn [m {:keys [ApplNo ProductNo] :as product}]
            (update m ApplNo #(conj (or % []) %2)
                    (-> (dissoc product :ApplNo)
                        (assoc :MarketingStatus (statuses [ApplNo ProductNo])))))
          {}))))

(defn application-doc-types [{:keys [ApplicationsDocsType_Lookup]}]
  (->> ApplicationsDocsType_Lookup
       (map
        (fn [{:keys [ApplicationDocsType_Lookup_Description
                     ApplicationDocsType_Lookup_ID]}]
          [ApplicationDocsType_Lookup_ID
           ApplicationDocsType_Lookup_Description]))
       (into {})))

(defn application-docs [{:keys [ApplicationDocs] :as data}]
  (let [types (application-doc-types data)]
    (->> ApplicationDocs
         (reduce
          (fn [m {:keys [ApplicationDocsTypeID ApplNo
                         SubmissionNo SubmissionType]
                  :as doc}]
            (update m [ApplNo SubmissionNo SubmissionType]
                    #(conj (or % []) %2)
                    (-> (dissoc doc :ApplicationDocsID :ApplicationDocsTypeID
                                :ApplNo :SubmissionNo :SubmissionType)
                        (assoc :ApplicationDocsDescription
                               (types ApplicationDocsTypeID)))))
          {}))))

(defn submission-classes [{:keys [SubmissionClass_Lookup]}]
  (->> SubmissionClass_Lookup
       (map
        (fn [{:keys [SubmissionClassCode SubmissionClassCodeDescription
                     SubmissionClassCodeID]}]
          [SubmissionClassCodeID
           {:Code SubmissionClassCode
            :Description SubmissionClassCodeDescription}]))
       (into {})))

(defn submissions [{:keys [Submissions] :as data}]
  (let [docs (application-docs data)
        classes (submission-classes data)]
    (->> Submissions
         (reduce
          (fn [m {:keys [ApplNo SubmissionClassCodeID
                         SubmissionNo SubmissionType]
                  :as sub}]
            (update m ApplNo #(conj (or % []) %2)
                    (-> (dissoc sub :ApplNo :SubmissionClassCodeID
                                :SubmissionNo)
                        (assoc :SubmissionClass (classes SubmissionClassCodeID)
                               :Docs
                               (docs [ApplNo SubmissionNo SubmissionType])))))
          {}))))

(defn applications [{:keys [Applications] :as data}]
  (let [products (products data)
        submissions (submissions data)]
    (->> Applications
         (map (fn [{:keys [ApplNo] :as app}]
                [ApplNo
                 (-> (dissoc app :ApplNo)
                     (assoc :Products (products ApplNo)
                            :Submissions (submissions ApplNo)))]))
         (into {}))))

(def parse-applications (comp applications parse-data))
