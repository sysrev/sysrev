(ns sysrev.label.answer
  (:require [sysrev.db.core :as db :refer [do-query with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.slack :as slack]
            [sysrev.label.core :as label]
            [clojure.tools.logging :as log]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.notification.interface :refer [create-notification]]
            [sysrev.project.core :as project]
            [sysrev.shared.labels :refer [cleanup-label-answer]]
            [sysrev.util :as util :refer [in?]]))

(defn label-answer-valid? [{:keys [label-id value-type definition] :as _label} answer]
  (println value-type)
  (case value-type
    "boolean"
    ; (when (contains? #{true false nil} answer)
      (when true
        {label-id answer})
    "categorical"
    (cond (nil? answer) {label-id answer}
          (sequential? answer) (let [allowed (set (:all-values definition))]
                                 (when (every? #(contains? allowed %) answer)
                                   {label-id answer})))
    "annotation"
    (cond (nil? answer) {label-id answer}
          (map? answer) {label-id answer})
    ;; TODO: check that answer value matches label regex
    "string"
    (when (coll? answer)
      (let [filtered (->> answer (filter string?) (filterv not-empty))]
        (cond (empty? filtered)          {label-id nil}
              (every? string? filtered)  {label-id filtered})))
    {label-id answer}))

(defn label-answer-inclusion [{:keys [label-id value-type definition] :as _label} answer]
  (let [ivals (:inclusion-values definition)]
    (case value-type
      "boolean"      (if (or (empty? ivals) (nil? answer))
                       nil
                       (boolean (in? ivals answer)))
      "categorical"  (if (or (empty? ivals) (empty? answer))
                       nil
                       (boolean (some (in? ivals) answer)))
      nil)))

(defn filter-valid-label-values [labels label-values]
  (->> label-values
       (mapv (fn [[label-id answer]]
               (let [label (get labels label-id)]
                 (and (:enabled label) (label-answer-valid? label answer)))))
       (remove nil?)
       (apply merge)))

(defn resolve-article-answers
  "Create new article_resolve entry to record a user resolving answers
  for an article at current time and for current consensus labels."
  [article-id user-id & {:keys [resolve-time label-ids]}]
  (with-transaction
    (let [project-id (q/get-article article-id :project-id)
          label-ids (or label-ids (project/project-consensus-label-ids project-id))
          resolve-time (or resolve-time db/sql-now)]
      (q/create :article-resolve [{:article-id article-id
                                   :user-id user-id
                                   :resolve-time resolve-time
                                   :label-ids (db/to-jsonb (vec label-ids))}]
                :returning :*))))

;; TODO: check that all required labels are answered
(defn set-user-article-labels
  "Set article-id for user-id with a map of label-values. imported? is
  vestigal, confirm? set to true will set the confirm_time to now,
  change? set to true will set the updated_time to now, resolve will
  be set to resolve? The format of label-values is {<label-id-1>
  <value-1>, <label-id-2> <value-2>, ... , <label-id-n> <value-n>}.

  It should be noted that a label's confirm value must be set to true
  in order to be recognized as a labeled article in the rest of the
  app. This includes article summary graphs, compensations,
  etc. e.g. If confirm? is not to set true, then a user would not be
  compensated for that article."
  [user-id article-id label-values &
   {:keys [imported? confirm? change? resolve?]}]
  (assert (integer? user-id))
  (assert (integer? article-id))
  (assert (map? label-values))
  (println "_______TEST________")
  (with-transaction
    (let [project-id (q/get-article article-id :project-id
                                    :with [], :include-disabled true)
          project-labels (project/project-labels project-id)
          valid-values (filter-valid-label-values project-labels label-values)
          now db/sql-now
          current-entries (when change?
                            (-> (q/select-article-by-id article-id [:al.*])
                                (q/join-article-labels)
                                (q/filter-label-user user-id)
                                (->> (do-query)
                                     (map #(-> (update % :answer db/to-jsonb)
                                               (update :imported boolean)
                                               (dissoc :article-label-id
                                                       :article-label-local-id))))))
          overall-label-id (project/project-overall-label-id project-id)
          confirm? (if imported?
                     (boolean (get valid-values overall-label-id))
                     confirm?)
          existing-label-ids (-> (q/select-article-by-id article-id [:al.label-id])
                                 (q/join-article-labels)
                                 (q/filter-label-user user-id)
                                 (->> do-query (map :label-id)))
          new-label-ids (->> (keys valid-values)
                             (remove (in? existing-label-ids)))
          new-entries (->> new-label-ids
                           (map (fn [label-id]
                                  (let [label (get project-labels label-id)
                                        answer (->> (get valid-values label-id)
                                                    (cleanup-label-answer label))
                                        inclusion (label-answer-inclusion label answer)]
                                    (assert (label-answer-valid? label answer))
                                    (cond-> {:label-id label-id
                                             :article-id article-id
                                             :user-id user-id
                                             :answer (db/to-jsonb answer)
                                             :added-time now
                                             :updated-time now
                                             :imported (boolean imported?)
                                             :resolve (boolean resolve?)
                                             :inclusion inclusion}
                                      confirm? (merge {:confirm-time now}))))))]
      (println project-labels)
      (println label-values)
      (doseq [label-id existing-label-ids]
        (when (contains? valid-values label-id)
          (let [label (get project-labels label-id)
                answer (->> (get valid-values label-id)
                            (cleanup-label-answer label))
                inclusion (label-answer-inclusion label answer)]
            (assert (label-answer-valid? label answer))
            (q/modify :article-label {:article-id article-id :user-id user-id :label-id label-id}
                      (cond-> {:answer (db/to-jsonb answer)
                               :updated-time now
                               :imported (boolean imported?)
                               :resolve (boolean resolve?)
                               :inclusion inclusion}
                        confirm? (assoc :confirm-time now))))))
      (println "__________TEST__________2")
      ; (println new-entries)
      (q/create :article-label new-entries)
      (when change?
        (q/create :article-label-history current-entries))
      (when confirm?
        (create-notification
         {:article-id article-id
          :article-data-title
          (q/find-one [:article :a]
                      {:a.article-id article-id}
                      :ad.title
                      :join [[:article-data :ad] [:= :a.article-data-id :ad.article-data-id]])
          :project-id project-id
          :project-name (q/find-one :project {:project-id project-id} :name)
          :user-id user-id
          :type :article-reviewed}))
      (when resolve?
        (resolve-article-answers article-id user-id :resolve-time now))
      (db/clear-project-cache project-id)
      true)))

;; Sets and optionally confirms label values for an article
(def exponential-steps (into [15] (->> (range 0 20) (map #(Math/pow 1.7 %)) (filter #(>= % 30)) (map int))))

(defn set-labels [{:keys [project-id user-id article-id label-values confirm? change? resolve? request] :as params}]
  (let [before-count (label/count-reviewed-articles project-id)
        duplicate-save? (and (label/user-article-confirmed? user-id article-id)
                             (not change?)
                             (not resolve?))]
    (if duplicate-save?
      (do (log/warnf "api/set-labels: answer already confirmed ; %s" (pr-str params))
          (slack/try-log-slack
            (if request
              [(format "*Request*:\n```%s```"
                       (util/pp-str (slack/request-info request)))]
              [])
            "Duplicate /api/set-labels request"))
      (set-user-article-labels user-id article-id label-values
                               :imported? false
                               :confirm? confirm?
                               :change? change?
                               :resolve? resolve?))
    (let [after-count (label/count-reviewed-articles project-id)]
      (when (and (> after-count before-count)
                 (not= 0 after-count)
                 (seq (filter #(= % after-count) exponential-steps)))
        (predict-api/schedule-predict-update project-id)))))
