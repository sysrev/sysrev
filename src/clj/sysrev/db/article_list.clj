(ns sysrev.db.article-list
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [sysrev.db.core :as db :refer
             [active-db do-query do-execute to-sql-array sql-now to-jsonb
              with-project-cache clear-project-cache]]
            [sysrev.db.project :as project]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            [sysrev.db.queries :as q]
            [sysrev.db.annotations :as ann]
            [sysrev.shared.util :as sutil :refer [in? map-values]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]))

(defn project-full-article-list [project-id]
  (with-project-cache
    project-id [:full-article-list]
    (let [predict-run-id (q/project-latest-predict-run-id project-id)
          articles (-> (q/select-project-articles
                        project-id [:a.article-id :a.primary-title :a.authors :a.keywords
                                    :a.date :a.year])
                       (->> do-query (group-by :article-id) (map-values first)))
          apredicts (when predict-run-id
                      (-> (q/select-project-articles
                           project-id [:lp.article-id :lp.label-id [:lp.val :score]])
                          (q/join-article-predict-values predict-run-id)
                          (->> do-query
                               (group-by :article-id)
                               (map-values
                                #(->> % (map (fn [{:keys [label-id score]}] {label-id score}))
                                      (apply merge)))
                               (map-values #(do {:predicts %})))))
          asources (-> (q/select-project-articles project-id [:a.article-id :asrc.source-id])
                       (q/join-article-source)
                       (merge-where [:= :a.project-id project-id])
                       (->> do-query
                            (group-by :article-id)
                            (map-values #(do {:sources (mapv :source-id %)}))))
          alabels (-> (q/select-project-articles
                       project-id [:al.article-id :al.label-id :al.user-id :al.answer :al.inclusion
                                   :al.updated-time :al.confirm-time :al.resolve])
                      (q/join-article-labels)
                      (q/filter-valid-article-label nil)
                      (->> do-query
                           (remove #(or (nil? (:answer %)) (and (sequential? (:answer %))
                                                                (empty? (:answer %)))))
                           (map #(-> (update % :updated-time tc/to-epoch)
                                     (update :confirm-time tc/to-epoch)))
                           (group-by :article-id) (map-values #(do {:labels %}))))
          aconsensus (->> (keys alabels) ;; article-ids
                          (map #(do {% {:consensus (label/article-consensus-status project-id %)
                                        :resolve (merge (label/article-resolved-status project-id %)
                                                        {:labels (label/article-resolved-labels
                                                                  project-id %)})}}))
                          (apply merge {}))
          anotes (-> (q/select-project-articles
                      project-id [:a.article-id :an.user-id :an.content :an.updated-time :pn.name])
                     (q/with-article-note)
                     (->> do-query
                          (remove (fn [{:keys [content]}]
                                    (or (nil? content) (and (string? content)
                                                            (empty? (str/trim content))))))
                          (map #(update % :updated-time tc/to-epoch))
                          (group-by :article-id) (map-values #(do {:notes %}))))
          annotations (->> (ann/project-annotation-articles project-id)
                           (map-values #(do {:annotations %})))]
      (->> (-> (merge-with merge articles alabels anotes annotations aconsensus asources apredicts)
               ;; ensure all map keys are present in primary articles map
               (select-keys (keys articles)))
           (map-values
            #(merge % {:updated-time (->> [(->> (:labels %) (map :updated-time))
                                           (->> (:notes %) (map :updated-time))
                                           [(some-> % :annotations :updated-time tc/to-epoch)]]
                                          (apply concat) (remove nil?) (apply max 0))}))))))

(defn article-ids-from-text-search [project-id text]
  (with-project-cache
    project-id [:text-search-ids text]
    (let [tokens (-> text (str/lower-case) (str/split #"[ \t\r\n]+"))]
      (->> (format "
SELECT article_id FROM article
WHERE project_id=%d
  AND enabled=true
  AND text_search @@ to_tsquery('%s');"
                   project-id
                   (->> tokens (map #(str "(" % ")")) (str/join " & ")))
           (j/query @active-db)
           (mapv :article_id)))))

(defn sort-article-id [sort-dir]
  (let [dir (if (= sort-dir :desc) > <)]
    #(sort-by :article-id dir %)))

(defn sort-updated-time [sort-dir]
  (fn [entries]
    ;; always sort entries with no updated-time value to the end
    (concat (cond-> (->> entries
                         (remove #(->> % :updated-time (in? [0 nil])))
                         (sort-by (fn [{:keys [updated-time article-id]}]
                                    [updated-time article-id])))
              (= sort-dir :desc) reverse)
            (cond-> (->> entries
                         (filter #(->> % :updated-time (in? [0 nil])))
                         (sort-by :article-id))
              (= sort-dir :desc) reverse))))

(defn filter-labels-confirmed [confirmed? labels]
  (assert (in? [true false nil] confirmed?))
  (cond->> labels
    (boolean? confirmed?) (filter (fn [{:keys [confirm-time]}]
                                    (let [entry-confirmed? (not (in? [nil 0] confirm-time))]
                                      (= entry-confirmed? confirmed?))))))

(defn filter-labels-by-id [label-id labels]
  (cond->> labels
    label-id (filter #(= (:label-id %) label-id))))

(defn filter-labels-by-values [label-id values labels]
  (cond->> (filter-labels-by-id label-id labels)
    (not-empty values) (filter (fn [{:keys [answer]}]
                                 (->> (if (sequential? answer) answer [answer])
                                      (some #(in? values %)))))))

(defn filter-labels-by-inclusion [label-id inclusion labels]
  (assert (in? [true false nil] inclusion))
  (cond->> (filter-labels-by-id label-id labels)
    (boolean? inclusion) (filter (fn [{:keys [answer]}]
                                   (= inclusion (answer/label-answer-inclusion label-id answer))))))

(defn filter-labels-by-users [user-ids labels]
  (cond->> labels
    (not-empty user-ids) (filter #(in? user-ids (:user-id %)))))

(defn article-source-filter [context {:keys [source-ids]}]
  (fn [article]
    (some #(in? source-ids %) (:sources article))))

;; TODO: include user notes in search
(defn article-text-filter [context text]
  (let [{:keys [project-id]} context
        article-ids (article-ids-from-text-search project-id text)]
    (fn [article]
      (in? article-ids (:article-id article)))))

(defn article-user-filter [context {:keys [user content confirmed]}]
  (fn [article]
    (let [labels (when (in? [nil :labels] content)
                   (cond->> (:labels article)
                     user  (filter-labels-by-users [user])
                     true  (filter-labels-confirmed confirmed)))
          have-annotations? (when (in? [nil :annotations] content)
                              (boolean (if (nil? user)
                                         (contains? article :annotations)
                                         (in? (-> article :annotations :users) user))))]
      (or (not-empty labels) have-annotations?))))

(defn article-labels-filter [context {:keys [label-id users values inclusion confirmed]}]
  (fn [article]
    (->> (:labels article)
         (filter-labels-by-users users)
         (filter-labels-by-values label-id values)
         (filter-labels-by-inclusion label-id inclusion)
         (filter-labels-confirmed confirmed)
         not-empty)))

(defn article-consensus-filter [context {:keys [status inclusion]}]
  (let [{:keys [project-id]} context
        overall-id (project/project-overall-label-id project-id)]
    (fn [{:keys [labels consensus article-id] :as article}]
      (let [overall (->> labels
                         (filter-labels-confirmed true)
                         (filter-labels-by-id overall-id))
            status-test (case status
                          :single      #(= :single consensus)
                          :determined  #(in? [:consistent :resolved] consensus)
                          :conflict    #(= :conflict consensus)
                          :consistent  #(= :consistent consensus)
                          :resolved    #(= :resolved consensus)
                          (constantly true))
            inclusion-test (if (nil? inclusion)
                             (constantly true)
                             #(if (or (= status :resolved) (and (= status :determined)
                                                                (= consensus :resolved)))
                                (-> (label/article-resolved-labels project-id article-id)
                                    (get overall-id) (= inclusion))
                                (-> (distinct (map :inclusion overall))
                                    (in? inclusion))))]
        (and (not-empty overall) (status-test) (inclusion-test))))))

(defn article-prediction-filter
  [context {:keys [label-id label-value direction score]}]
  (let [compare-score (case direction  :above >, :below <)]
    (fn [{:keys [predicts] :as article}]
      (some-> (get predicts label-id)
              (#(if (false? label-value) (- 1.0 %) %))
              (compare-score (/ score 100.0))))))

(defn get-sort-fn [sort-by sort-dir]
  (case sort-by
    :article-added    (sort-article-id sort-dir)
    :content-updated  (sort-updated-time sort-dir)
    (sort-article-id sort-dir)))

(defn get-filter-fn [context]
  (fn [fmap]
    (let [filter-name (first (keys fmap))
          {:keys [negate]} (first (vals fmap))
          make-filter (case filter-name
                        :text-search       article-text-filter
                        :source            article-source-filter
                        :has-user          article-user-filter
                        :consensus         article-consensus-filter
                        :has-label         article-labels-filter
                        :prediction        article-prediction-filter
                        (constantly (constantly true)))]
      (comp (if negate not identity)
            (make-filter context (get fmap filter-name))))))

(defn project-article-list-filtered
  [{:keys [project-id] :as context} filters sort-by sort-dir]
  (with-project-cache
    project-id [:filtered-article-list [filters sort-by sort-dir]]
    (let [sort-fn (get-sort-fn sort-by sort-dir)
          filter-fns (mapv (get-filter-fn context) filters)
          filter-all-fn (if (empty? filters)
                          (constantly true)
                          (apply every-pred filter-fns))]
      (->> (vals (project-full-article-list project-id))
           (filter filter-all-fn)
           (sort-fn)))))

(defn query-project-article-list
  [project-id {:keys [filters sort-by sort-dir n-offset n-count user-id]
               :or {filters []
                    sort-by :content-updated
                    sort-dir :desc
                    n-offset 0
                    n-count 20
                    user-id nil}}]
  (let [context {:project-id project-id :user-id user-id}
        entries (project-article-list-filtered context filters sort-by sort-dir)]
    {:entries (->> entries (drop n-offset) (take n-count))
     :total-count (count entries)}))

(defn query-project-article-ids
  [{:keys [project-id] :as context} filters &
   {:keys [sort-by sort-dir]}]
  (let [sort-fn (or (some-> sort-by (get-sort-fn sort-dir))
                    identity)
        filter-fns (mapv (get-filter-fn context) filters)
        filter-all-fn (or (some->> filter-fns not-empty (apply every-pred))
                          (constantly true))]
    (->> (vals (project-full-article-list project-id))
         (filter filter-all-fn)
         (sort-fn)
         (map :article-id))))
