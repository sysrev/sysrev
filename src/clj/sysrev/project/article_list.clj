(ns sysrev.project.article-list
  (:require [clojure.string :as str]
            [clj-time.coerce :as tc]
            [honeysql.core :as sql]
            [honeysql.helpers :refer [merge-where]]
            [medley.core :as medley]
            [sysrev.db.core :as db :refer [do-query with-project-cache]]
            [sysrev.project.core :as project]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            [sysrev.db.queries :as q]
            [sysrev.annotations :refer [project-article-annotations]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.util :as util :refer [in? index-by]]))

;;;
;;; Article data lookup functions
;;;

(defn project-article-predicts [project-id label-value-aux]
  (let [label-value (if (boolean? label-value-aux)
                      (if label-value-aux "TRUE" "FALSE")
                      label-value-aux)]
    (with-project-cache project-id [:article-list :predicts label-value]
      (when-let [predict-run-id (q/project-latest-predict-run-id project-id)]
        (-> (q/select-project-articles
             project-id [:lp.article-id :lp.label-id [:lp.val :score]])
            (-> (q/join-article-predict-values predict-run-id)
                (merge-where [:= :lp.label-value label-value]))
            (->> do-query
                 (group-by :article-id)
                 (medley/map-vals
                  #(->> % (map (fn [{:keys [label-id score label-value]}] {label-id score}))
                        (apply merge)))))))))

(defn project-article-sources [project-id]
  (with-project-cache project-id [:article-list :sources]
    (-> (q/select-project-articles project-id [:a.article-id :asrc.source-id])
        (q/join-article-source)
        (->> do-query
             (group-by :article-id)
             (medley/map-vals #(mapv :source-id %))))))

(defn project-article-labels [project-id]
  (with-project-cache project-id [:article-list :article-labels]
    (-> (q/select-project-articles
         project-id [:al.article-id :al.label-id :al.user-id :al.answer :al.inclusion
                     :al.updated-time :al.confirm-time :al.resolve])
        (q/join-article-labels)
        (q/filter-valid-article-label nil)
        (->> do-query
             (remove #(or (nil? (:answer %)) (and (sequential? (:answer %))
                                                  (empty? (:answer %)))))
             (map #(-> (update % :updated-time tc/to-epoch)
                       (update :confirm-time tc/to-epoch)))
             (group-by :article-id)))))

(defn project-article-consensus [project-id]
  (with-project-cache project-id [:article-list :consensus]
    (apply merge {}
           (for [article-id (keys (project-article-labels project-id))]
             {article-id
              {:consensus (label/article-consensus-status project-id article-id)
               :resolve (merge (label/article-resolved-status project-id article-id)
                               {:labels (label/article-resolved-labels project-id article-id)})}}))))

(defn project-article-notes [project-id]
  (with-project-cache project-id [:article-list :notes]
    (->> (q/find-article {:a.project-id project-id}
                         [:a.article-id :an.user-id :an.content :an.updated-time]
                         :with [:article-note])
         (filter #(some-> % :content str/trim not-empty))
         (map #(update % :updated-time tc/to-epoch))
         (group-by :article-id))))

(defn project-article-updated-time [_ article-id & [all-labels all-notes all-annotations]]
  (let [labels (get all-labels article-id)
        notes  (get all-notes article-id)
        annotations (get all-annotations article-id)]
    (->> [(map :updated-time labels)
          (map :updated-time notes)
          [(some-> annotations :updated-time tc/to-epoch)]]
         (apply concat) (remove nil?) (apply max 0))))

;;;
;;; Article sort functions
;;;

(defn sort-article-id [sort-dir]
  (let [dir (if (= sort-dir :desc) > <)]
    #(sort-by :article-id dir %)))

(defn sort-updated-time [sort-dir]
  (fn [entries]
    ;; always sort entries with no updated-time value to the end
    (concat (cond-> (->> entries
                         (remove #(->> % :updated-time (in? [0 nil])))
                         (sort-by :updated-time))
              (= sort-dir :desc) reverse)
            (cond-> (->> entries
                         (filter #(->> % :updated-time (in? [0 nil])))
                         (sort-by :article-id))
              (= sort-dir :desc) reverse))))

(defn get-sort-fn [sort-by sort-dir]
  (case sort-by
    :article-added    (sort-article-id sort-dir)
    :content-updated  (sort-updated-time sort-dir)
    (sort-article-id sort-dir)))

;;;
;;; Filter helper functions
;;;

(defn article-ids-from-title-search-local [project-id text]
  (with-project-cache project-id [:text-search-ids :local-title text]
    (q/find [:article :a] {:a.project-id project-id :a.enabled true}
            :article-id, :join [[:article-data :ad] :a.article-data-id]
            :where [:textmatch :ad.title-search
                    (sql/call :plainto_tsquery (sql/raw "'english'") (str/lower-case text))])))

(defn article-ids-from-content-search-local [project-id text]
  (with-project-cache project-id [:text-search-ids :local-content text]
    (q/find [:article :a] {:a.project-id project-id :a.enabled true}
            :article-id, :join [[:article-data :ad] :a.article-data-id]
            :where [:textmatch :ad.content-search
                    (sql/call :plainto_tsquery (sql/raw "'english'") (str/lower-case text))])))

(defn article-ids-from-text-search-remote [project-id text]
  (with-project-cache project-id [:text-search-ids :remote text]
    (let [pmid->id (->> (q/find-article {:a.project-id project-id :ad.datasource-name "pubmed"}
                                        :article-id, :index-by :external-id
                                        :where [:!= :ad.external-id nil])
                        (medley/map-keys util/parse-integer))]
      (mapv (partial get pmid->id)
            (ds-api/search-text-by-pmid text (sort (keys pmid->id)))))))

(defn article-ids-from-text-search [project-id text]
  (with-project-cache project-id [:text-search-ids :all text]
    (let [[local-ids remote-ids]
          (pvalues (concat (article-ids-from-title-search-local project-id text)
                           (article-ids-from-content-search-local project-id text))
                   (article-ids-from-text-search-remote project-id text))]
      (distinct (concat local-ids remote-ids)))))

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

;;;
;;; Top-level filter functions
;;;

(defn article-source-filter [{:keys [project-id] :as _context} {:keys [source-ids]}]
  (let [sources (project-article-sources project-id)]
    (fn [{:keys [article-id]}]
      (some (in? source-ids) (get sources article-id)))))

;; TODO: include user notes in search
(defn article-text-filter [{:keys [project-id] :as _context} text]
  (let [search-ids (set (article-ids-from-text-search project-id text))]
    (fn [{:keys [article-id]}]
      (contains? search-ids article-id))))

(defn article-user-filter
  [{:keys [project-id] :as _context} {:keys [user content confirmed]}]
  (let [all-labels (project-article-labels project-id)
        all-annotations (project-article-annotations project-id)]
    (fn [{:keys [article-id]}]
      (let [article-labels (get all-labels article-id)
            annotations (get all-annotations article-id)
            labels (when (in? [nil :labels] content)
                     (cond->> article-labels
                       user  (filter-labels-by-users [user])
                       true  (filter-labels-confirmed confirmed)))
            have-annotations? (when (in? [nil :annotations] content)
                                (boolean (if (nil? user)
                                           (not-empty annotations)
                                           (in? (:users annotations) user))))]
        (or (not-empty labels) have-annotations?)))))

(defn article-labels-filter
  [{:keys [project-id] :as _context} {:keys [label-id users values inclusion confirmed]}]
  (let [all-labels (project-article-labels project-id)]
    (fn [{:keys [article-id]}]
      (->> (get all-labels article-id)
           (filter-labels-by-users users)
           (filter-labels-by-values label-id values)
           (filter-labels-by-inclusion label-id inclusion)
           (filter-labels-confirmed confirmed)
           not-empty))))

(defn article-consensus-filter
  [{:keys [project-id] :as _context} {:keys [status inclusion]}]
  (let [status (keyword status)
        overall-id (project/project-overall-label-id project-id)
        all-labels (project-article-labels project-id)
        all-consensus (project-article-consensus project-id)]
    (fn [{:keys [article-id]}]
      (let [labels (get all-labels article-id)
            consensus (get-in all-consensus [article-id :consensus])
            overall (->> labels
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
  [{:keys [project-id] :as _context} {:keys [label-id label-value direction score]}]
  (let [all-predicts (project-article-predicts project-id label-value)
        score (* 0.01 score)
        pred (case (if (string? direction) (keyword direction) direction)
               :above >
               :below <)]
    (fn [{:keys [article-id]}]
      (some-> (get-in all-predicts [article-id label-id])
              (pred score)))))

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

;;;
;;; Article list search
;;;

(defn project-article-list-filtered
  [_sr-context & {:keys [filters project-id sort-by sort-dir] :as opts}]
  (with-project-cache project-id [:filtered-article-list [filters sort-by sort-dir]]
    (let [;; get these in parallel first, always needed for updated-time
          article-ids (project/project-article-ids project-id true)
          labels (project-article-labels project-id)
          notes (project-article-notes project-id)
          annotations (project-article-annotations project-id)
          sort-fn (get-sort-fn sort-by sort-dir)
          filter-fns (mapv (get-filter-fn opts) filters)
          filter-all-fn (if (empty? filters)
                          (constantly true)
                          (apply every-pred filter-fns))]
      (->> article-ids
           (map #(hash-map :article-id %))
           (filter filter-all-fn)
           (map #(assoc % :updated-time (project-article-updated-time project-id (:article-id %)
                                                                      labels notes annotations)))
           (sort-fn)))))

(defn lookup-article-entries [project-id article-ids]
  (when (seq article-ids)
    (->> (q/find-article {:a.project-id project-id :a.article-id article-ids}
                         [:a.article-id :ad.title])
         (map (fn [{:keys [article-id title] :as a}]
                (-> (assoc a :primary-title title)
                    (dissoc :title)
                    (merge {:labels (get (project-article-labels project-id) article-id)
                            :notes  (get (project-article-notes project-id) article-id)}
                           (get (project-article-consensus project-id) article-id)))))
         (index-by :article-id))))

(defn query-project-article-list
  [sr-context project-id
   & {:keys [filters sort-by sort-dir n-offset n-count user-id]
      :or {filters []
           sort-by :content-updated
           sort-dir :desc
           n-offset 0
           n-count 20
           user-id nil}}]
  (let [all-entries (project-article-list-filtered
                     sr-context :filters filters :project-id project-id
                     :sort-by sort-by :sort-dir sort-dir)
        display-entries (->> all-entries (drop n-offset) (take n-count))
        articles (lookup-article-entries project-id (map :article-id display-entries))]
    {:entries (->> display-entries (mapv #(merge % (get articles (:article-id %)))))
     :total-count (count all-entries)}))

(defn query-project-article-ids
  [{:keys [project-id] :as context} filters &
   {:keys [sort-by sort-dir]}]
  (let [sort-fn (or (some-> sort-by (get-sort-fn sort-dir))
                    identity)
        filter-fns (mapv (get-filter-fn context) filters)
        filter-all-fn (or (some->> filter-fns not-empty (apply every-pred))
                          (constantly true))]
    (->> (project/project-article-ids project-id true)
         (map #(hash-map :article-id %))
         (filter filter-all-fn)
         (sort-fn)
         (map :article-id))))
