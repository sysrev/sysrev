(ns sysrev.export.core
  (:require [clojure.string :as str]
            [honeysql.helpers :as sqlh :refer [order-by merge-where merge-join]]
            [medley.core :as medley]
            [sysrev.api :refer [graphql-request]]
            [sysrev.db.core :refer [do-query with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.annotations :as ann]
            [sysrev.label.core :as label]
            [sysrev.project.core :as project]
            [sysrev.datasource.api :as ds-api]
            [sysrev.util :as util :refer [in? map-values index-by]]
            [venia.core :as venia]))

(def default-csv-separator "|||")

(defn stringify-csv-value
  "Formats x as a string to use as a CSV column value."
  [separator x]
  (let [separator (or separator default-csv-separator)]
    (cond (and (seqable? x) (empty? x))  ""
          (sequential? x)                (str/join separator (map str x))
          :else                          (str x))))

(defn project-labeled-article-ids [project-id]
  (q/find-article
   {:a.project-id project-id} :a.article-id
   :where (q/exists [:article-label :al] {:a.project-id project-id
                                          :al.article-id :a.article-id
                                          :l.enabled true}
                    :join [[:label :l] :al.label-id]
                    :prepare #(q/filter-valid-article-label % true))
   :with []))

(defn export-user-answers-csv
  "Returns CSV-printable list of raw user article answers. The first row
  contains column names; each following row contains answers for one
  value of (user,article). article-ids optionally specifies a subset
  of articles within project to include; by default, all enabled
  articles will be included."
  [project-id & {:keys [article-ids separator]}]
  (with-transaction
    (let [all-labels (-> (q/select-label-where project-id true [:label-id :short-label])
                         (order-by :project_ordering :label-id-local) do-query)
          all-articles (-> (project-labeled-article-ids project-id)
                           (ds-api/get-articles-content))
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
           (mapv (partial stringify-csv-value separator)
                 (concat [article-id user-name (if (true? resolved?) true nil)]
                         label-answers
                         [user-note primary-title secondary-title all-authors]))))))))

(defn export-group-answers-csv
  "Returns CSV-printable list of combined group answers for
  articles. The first row contains column names; each following row
  contains answers for one article. article-ids optionally specifies a
  subset of articles within project to include; by default, all
  enabled articles will be included."
  [project-id & {:keys [article-ids separator]}]
  (with-transaction
    (let [project-url (str "https://sysrev.com/p/" project-id)
          all-labels (-> (q/select-label-where project-id true [:label-id :short-label])
                         (order-by :project_ordering :label-id-local) do-query)
          all-articles (-> (project-labeled-article-ids project-id)
                           (ds-api/get-articles-content))
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
           (mapv (partial stringify-csv-value separator)
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
  [project-id & {:keys [article-ids separator]}]
  (with-transaction
    (let [project-url (str "https://sysrev.com/p/" project-id)
          article-ids (or (seq article-ids) (project/project-article-ids project-id))
          all-articles (ds-api/get-articles-content article-ids)
          predict-run-id (q/project-latest-predict-run-id project-id)
          predict-label-ids [(project/project-overall-label-id project-id)]
          ;; TODO: select labels by presence of label_predicts entries
          predict-labels (when (and predict-run-id (seq predict-label-ids))
                           (-> (q/select-label-where
                                project-id [:in :l.label-id predict-label-ids]
                                [:l.label-id :l.label-id-local :l.short-label])
                               (->> do-query (index-by :label-id))))
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
           (mapv (partial stringify-csv-value separator)
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
  [project-id & {:keys [article-ids separator]}]
  (with-transaction
    (concat
     [["Article ID" "User ID" "Annotation" "Semantic Class" "Selection"
       "Start Offset" "End Offset" "Article Field" "Filename" "File Key"]]
     (for [{:keys [article-id annotation selection context definition user-id filename file-key]}
           (->> (ann/project-annotations-basic project-id)
                (sort-by #(vector (:article-id %) (:user-id %))))]
       (let [{:keys [start-offset end-offset text-context]} context
             {:keys [field]} text-context]
         (mapv (partial stringify-csv-value separator)
               [article-id user-id annotation definition selection
                start-offset end-offset field filename file-key]))))))


(defn export-group-label-csv
  "Export the group label-id in project-id"
  [project-id & {:keys [article-ids separator label-id]}]
  (with-transaction
    (let [graphql-resp (->> (graphql-request
                             (venia/graphql-query
                              {:venia/queries
                               [[:project {:id project-id}
                                 [:name :id :date_created
                                  [:groupLabelDefinitions [:enabled :name :question :required :type :id :ordering [:labels [:id :consensus :enabled :name :question :required :type :ordering]]]]
                                  [:articles [:enabled :id
                                              [:groupLabels [[:answer
                                                              [:id :answer :name :question :required :type]]
                                                             :confirmed :consensus :created :id :name :question :required :updated :type
                                                             [:reviewer [:id :name]]]]]]]]]}))
                            :data :project
                            util/sanitize-uuids)
          group-label-defs (->> (get-in graphql-resp [:groupLabelDefinitions])
                                (medley/find-first #(= (:id %) label-id))
                                :labels
                                (filterv :enabled)
                                (medley/index-by :id))
          header (->> (concat ["Article ID" "User ID" "User Name"] (mapv :name (->> (vals group-label-defs)
                                                                                    (sort-by :ordering))))
                      (into []))
          process-group-answers (fn [answers]
                                  (->> answers
                                       (mapv
                                        #(assoc % :ordering (:ordering (get group-label-defs (:id %)))))
                                       (sort-by :ordering)
                                       (mapv (fn [{:keys [answer]}]
                                               (stringify-csv-value separator answer)))))
          process-group-labels (fn [group-label]
                                 (let [{:keys [id name]} (:reviewer group-label)
                                       answer  (:answer group-label)]
                                   (->> (mapv process-group-answers answer)
                                        (mapv (partial concat [(str id) name])))))
          process-articles (fn [article]
                             (let [article-id (:id article)]
                               article-id
                               (->> article
                                    :groupLabels
                                    (filterv #(= (:id %) label-id))
                                    (mapv process-group-labels)
                                    (mapv (partial concat [(str article-id)])))))
          rows (->> graphql-resp
                    :articles
                    (mapv process-articles)
                    (remove (comp not seq))
                    (mapv #(->> %
                                (mapv (fn [coll]
                                        (mapv (partial concat [(first coll)]) (rest coll))))
                                (apply concat)))
                    (apply concat)
                    (into []))]
      (cons header rows))))
