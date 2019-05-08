(ns sysrev.db.export
  (:require [clojure.string :as str]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer [do-query with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.db.annotations :as ann]
            [sysrev.label.core :as label]
            [sysrev.db.project :as project]
            [sysrev.shared.util :refer [in? map-values ->map-with-key]]))

(defn stringify-csv-value
  "Formats x as a string to use as a CSV column value."
  [x]
  (cond (and (seqable? x) (empty? x))  ""
        (sequential? x)                (str/join ", " (map str x))
        :else                          (str x)))

(defn export-user-answers-csv
  "Returns CSV-printable list of raw user article answers. The first row
  contains column names; each following row contains answers for one
  value of (user,article). article-ids optionally specifies a subset
  of articles within project to include; by default, all enabled
  articles will be included."
  [project-id & {:keys [article-ids]}]
  (with-transaction
    (let [all-labels (-> (q/select-label-where project-id true [:label-id :short-label])
                         (order-by :label-id-local) do-query)
          all-articles (-> (q/select-project-articles
                            project-id [:a.article-id :a.primary-title :a.secondary-title :a.authors])
                           (merge-where [:exists (-> (q/select-project-article-labels
                                                      project-id true [:al.*])
                                                     (merge-where [:= :l.enabled true]))])
                           (->> do-query (->map-with-key :article-id)))
          user-answers (-> (q/select-project-articles
                            project-id [:al.article-id :al.label-id :al.user-id :al.answer
                                        :al.resolve :al.updated-time :u.email])
                           (q/join-article-labels)
                           (q/filter-valid-article-label true)
                           (q/join-article-label-defs)
                           (merge-where [:= :l.enabled true])
                           (q/join-users :al.user-id)
                           (->> do-query
                                (group-by #(vector (:article-id %) (:user-id %)))
                                vec (sort-by first) (mapv second)))
          user-notes (-> (q/select-project-articles
                          project-id [:anote.article-id :anote.user-id :anote.content])
                         (merge-join [:article-note :anote]
                                     [:= :anote.article-id :a.article-id])
                         (->> do-query
                              (group-by #(vector (:article-id %) (:user-id %)))
                              (map-values first)))]
      (concat
       [(concat ["Article ID" "User Name" "Resolve?"]
                (map :short-label all-labels)
                ["User Note" "Title" "Journal" "Authors"])]
       (for [user-article (cond->> user-answers
                            (seq article-ids)
                            (filter #(in? article-ids (-> % first :article-id))))]
         (let [{:keys [article-id user-id email]} (first user-article)
               resolved? (-> (:user-id (label/article-resolved-status project-id article-id))
                             (= user-id))
               user-name (first (str/split email #"@"))
               user-note (:content (get user-notes [article-id user-id]))
               {:keys [primary-title secondary-title authors]} (get all-articles article-id)
               label-answers (->> (map :label-id all-labels)
                                  (map (fn [label-id] (->> user-article
                                                           (filter #(= (:label-id %) label-id))
                                                           first :answer))))
               all-authors (str/join "; " (map str authors))]
           (mapv stringify-csv-value
                 (concat [article-id user-name (if (true? resolved?) true nil)]
                         label-answers
                         [user-note primary-title secondary-title all-authors]))))))))

(defn export-group-answers-csv
  "Returns CSV-printable list of combined group answers for
  articles. The first row contains column names; each following row
  contains answers for one article. article-ids optionally specifies a
  subset of articles within project to include; by default, all
  enabled articles will be included."
  [project-id & {:keys [article-ids]}]
  (with-transaction
    (let [project-url (str "https://sysrev.com/p/" project-id)
          all-labels (-> (q/select-label-where project-id true [:label-id :short-label])
                         (order-by :label-id-local) do-query)
          all-articles (-> (q/select-project-articles
                            project-id [:a.article-id :a.primary-title :a.secondary-title :a.authors])
                           (merge-where [:exists (-> (q/select-project-article-labels
                                                      project-id true [:al.*])
                                                     (merge-where [:= :l.enabled true]))])
                           (->> do-query (->map-with-key :article-id)))
          anotes (-> (q/select-project-articles
                      project-id [:anote.article-id :anote.user-id :anote.content])
                     (merge-join [:article-note :anote]
                                 [:= :anote.article-id :a.article-id])
                     (->> do-query (group-by :article-id)))
          aanswers (-> (q/select-project-articles
                        project-id [:al.article-id :al.label-id :al.user-id :al.answer
                                    :al.updated-time :u.email])
                       (q/join-article-labels)
                       (q/filter-valid-article-label true)
                       (q/join-article-label-defs)
                       (merge-where [:= :l.enabled true])
                       (q/join-users :al.user-id)
                       (->> do-query (group-by :article-id)))]
      (concat
       [(concat ["Article ID" "Article URL" "Status" "User Count" "Users"]
                (map :short-label all-labels)
                ["User Notes" "Title" "Journal" "Authors"])]
       (for [article-id (sort (keys (cond-> aanswers
                                      (seq article-ids) (select-keys article-ids))))]
         (let [{:keys [primary-title secondary-title authors]} (get all-articles article-id)
               user-names (->> (get aanswers article-id) (map :email) distinct
                               (map #(first (str/split % #"@"))))
               user-count (count user-names)
               user-notes (map :content (get anotes article-id))
               consensus (label/article-consensus-status project-id article-id)
               resolved-labels (label/article-resolved-labels project-id article-id)
               get-label-values (fn [label-id]
                                  (as-> (if (seq resolved-labels)
                                          (get resolved-labels label-id)
                                          (->> (get aanswers article-id)
                                               (filter #(= (:label-id %) label-id))
                                               (map :answer)
                                               (map #(if (sequential? %) % [%]))
                                               (apply concat) distinct sort)) xs
                                    (if (sequential? xs) xs [xs])))
               all-authors (str/join "; " (map str authors))
               all-notes (str/join "; " (map pr-str user-notes))
               article-url (str project-url "/article/" article-id)]
           (mapv stringify-csv-value
                 (concat [article-id article-url (name (or consensus :none))
                          user-count user-names]
                         (->> all-labels (map (comp get-label-values :label-id)))
                         [all-notes primary-title secondary-title all-authors]))))))))

;; TODO: include article external urls in export
(defn export-articles-csv
  "Returns CSV-printable list of articles with their basic fields. The
  first row contains column names; each following row contains fields
  for one article. Article prediction scores are also
  included. article-ids optionally specifies a subset of articles
  within project to include; by default, all enabled articles will be
  included."
  [project-id & {:keys [article-ids]}]
  (with-transaction
    (let [project-url (str "https://sysrev.com/p/" project-id)
          article-ids (or (seq article-ids) (project/project-article-ids project-id))
          all-articles (->> (q/query-multiple-by-id
                             :article [:article-id :primary-title :secondary-title
                                       :authors :abstract]
                             :article-id article-ids
                             :where [:= :enabled true])
                            (->map-with-key :article-id))
          predict-run-id (q/project-latest-predict-run-id project-id)
          predict-label-ids [(project/project-overall-label-id project-id)]
          ;; TODO: select labels by presence of label_predicts entries
          predict-labels (when (and predict-run-id (seq predict-label-ids))
                           (-> (q/select-label-where
                                project-id [:in :l.label-id predict-label-ids]
                                [:l.label-id :l.label-id-local :l.short-label])
                               (->> do-query (->map-with-key :label-id))))
          apredicts (when (and predict-run-id (seq predict-label-ids))
                      (-> (q/select-project-articles
                           project-id [:lp.article-id :lp.label-id [:lp.val :score]])
                          (q/join-article-predict-values predict-run-id)
                          (merge-where [:in :lp.label-id predict-label-ids])
                          (->> do-query
                               (group-by :article-id)
                               (map-values (partial map #(dissoc % :article-id)))
                               (map-values #(->> (group-by :label-id %)
                                                 ((partial map-values (comp :score first))))))))]
      (concat
       [(concat ["Article ID" "Article URL" "Title" "Journal" "Authors" "Abstract"]
                (for [label-id predict-label-ids]
                  (let [x (get predict-labels label-id)]
                    (str "predict(" (:label-id-local x) ":" (:short-label x) ")"))))]
       (for [article-id (sort (keys all-articles))]
         (let [{:keys [primary-title secondary-title authors
                       abstract]} (get all-articles article-id)
               all-authors (str/join "; " (map str authors))
               article-url (str project-url "/article/" article-id)
               predict-scores (for [label-id predict-label-ids]
                                (when-let [score (get-in apredicts [article-id label-id])]
                                  (format "%.3f" score)))]
           (mapv stringify-csv-value
                 (concat [article-id article-url primary-title secondary-title
                          all-authors abstract]
                         predict-scores))))))))

;; TODO: include some user name in entries, or in another csv file
(defn export-annotations-csv
  "Returns CSV-printable list of user annotations in project. The first
  row contains column names; each following row contains fields for
  one annotation. article-ids optionally specifies a subset of
  articles within project to include; by default, all enabled articles
  will be included."
  [project-id & {:keys [article-ids]}]
  (with-transaction
    (concat
     [["Article ID" "User ID" "Annotation" "Semantic Class" "Selection"
       "Start Offset" "End Offset" "Article Field" "Filename" "File Key"]]
     (for [{:keys [article-id annotation selection context definition user-id filename file-key]}
           (->> (ann/project-annotations-basic project-id)
                (sort-by #(vector (:article-id %) (:user-id %))))]
       (let [{:keys [start-offset end-offset text-context]} context
             {:keys [field]} text-context]
         (mapv stringify-csv-value
               [article-id user-id annotation definition selection
                start-offset end-offset field filename file-key]))))))
