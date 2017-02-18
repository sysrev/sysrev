(ns sysrev.state.project
  (:require [sysrev.state.core :as s :refer [data user]]
            [sysrev.util :refer [short-uuid in?]]
            [sysrev.shared.predictions :as predictions]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]))


(defn project [& ks]
  (when-let [project-id (s/current-project-id)]
    (data (concat [:project project-id] ks))))

(defn merge-article [article]
  (fn [s]
    (update-in s [:data :articles]
               #(assoc % (:article-id article) article))))

(defn merge-articles [articles]
  (fn [s]
    (update-in s [:data :articles] #(merge-with merge % articles))))

;; This is for URLs for PDF files referenced in `article` under `:document-ids`.
(defn merge-documents [documents]
  (fn [s]
    (update-in s [:data :documents] #(merge % documents))))

(defn set-project-info [{:keys [project-id] :as pmap}]
  (fn [s]
    (let [;; need to merge in :member-labels field because it's loaded from a
          ;; separate request (/api/member-labels)
          old-member-labels (data [:project (:project-id pmap) :member-labels])
          new-member-labels (:member-labels pmap)
          pmap
          (assoc pmap
                 :overall-label-id
                 (->> (:labels pmap)
                      (filter (fn [[label-id {:keys [name]}]]
                                (= name "overall include")))
                      first first)
                 :member-labels
                 (merge old-member-labels new-member-labels))
          metric-keys #(select-keys % [:true-positives :true-negatives :false-positives :false-negatives :global-population-size])
          transformed #(transform-keys ->kebab-case-keyword %)
          confidence-update #(update % :values (comp predictions/map->AccuracyMetric metric-keys transformed))
          confidences-update #(map confidence-update %)
          parsed-pmap (update-in pmap [:stats :predict :confidences] confidences-update)]
      (assert (:project-id parsed-pmap))
      (assoc-in s [:data :project (:project-id parsed-pmap)] parsed-pmap))))

(defn article-documents [article-id]
  (when-let [article (data [:articles article-id])]
    (let [doc-ids (:document-ids article)]
      (->> doc-ids
           (map
            (fn [doc-id]
              (let [fnames (data [:documents (js/parseInt doc-id)])]
                (->> fnames
                     (map (fn [fname]
                            {:document-id doc-id
                             :file-name fname}))))))
           (apply concat)
           vec))))

(defn article-document-url [doc-id file-name]
  (str "/files/PDF/" doc-id "/" file-name))

(defn project-user [user-id & ks]
  (apply project :users user-id ks))

(defn article-location-urls [locations]
  (let [sources [:pubmed :doi :pii #_ :nct]]
    (->>
     sources
     (map
      (fn [source]
        (let [entries (get locations source)]
          (->>
           entries
           (map
            (fn [{:keys [external-id]}]
              (case source
                :pubmed (str "https://www.ncbi.nlm.nih.gov/pubmed/?term=" external-id)
                :doi (str "https://dx.doi.org/" external-id)
                :pmc (str "https://www.ncbi.nlm.nih.gov/pmc/articles/" external-id "/")
                :nct (str "https://clinicaltrials.gov/ct2/show/" external-id)
                nil)))))))
     (apply concat)
     (filter identity))))

(defn project-id-from-hash [project-hash]
  ((comp :project-id first)
   (->> (vals (data [:all-projects]))
        (filter #(= project-hash
                    (-> % :project-uuid short-uuid))))))

(defn project-hash-from-id [project-id]
  (short-uuid (data [:all-projects project-id :project-uuid])))

(defn project-invite-url [project-id]
  (str "https://sysrev.us/register/" (project-hash-from-id project-id)))

(defn project-member-user-ids [include-self-admin?]
  (let [self-id (s/current-user-id)]
    (->> (keys (project :members))
         (filter
          (fn [user-id]
            (let [permissions (user user-id :permissions)]
              (or (not (in? permissions "admin"))
                  (and self-id include-self-admin?
                       (= user-id self-id))))))
         (sort <))))
