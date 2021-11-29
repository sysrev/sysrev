(ns sysrev.fda-drugs.core
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [hickory.core :as hi]
            [hickory.zip :as hz]
            [sysrev.file-util.interface :as file-util])
  (:import (java.net URL)
           (java.nio.file Path)))

;; Reference: https://www.fda.gov/drugs/drug-approvals-and-databases/drugsfda-data-files
;; ActionTypes_Lookup and SubmissionPropertyType are omitted.

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

(defn text-content [{:keys [content]}]
  (->> (mapcat #(cond (nil? %) nil (string? %) [%] :else (text-content %)) content)
       (apply str)))

(defn parse-review-html-content
  "Finds <a> tags with targets ending in .pdf.

  Returns a seq of {:label \"\" :url \"\"} maps. URLs may be relative or absolute."
  [node]
  (loop [zipper (hz/hickory-zip node)
         docs []]
    (let [{:keys [attrs tag] :as node} (zip/node zipper)
          docs* (if (and (= :a tag) (some-> attrs :href str/lower-case (str/ends-with? ".pdf")))
                  (conj docs {:label (some-> node text-content str/trim
                                             (str/replace #"\s+" " "))
                              :url (:href attrs)})
                  docs)]
      (if (zip/end? zipper)
        docs*
        (recur (zip/next zipper) docs*)))))

(defn parse-review-html [html]
  (loop [zipper (-> html hi/parse hi/as-hickory hz/hickory-zip)]
    (let [{:keys [attrs tag] :as node} (zip/node zipper)]
      (if (and (= :div tag) (or (= "content" (some-> attrs :id str/lower-case))
                                (some #{"content"} (some-> attrs :class
                                                           (str/split #"\s+")
                                                           (->> (map str/lower-case))))))
        (parse-review-html-content node)
        (when-not (zip/end? zipper)
          (recur (zip/next zipper)))))))
