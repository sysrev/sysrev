(ns sysrev.project.clone
  (:require [clojure.set :refer [difference]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [from insert-into join select
                                               values where]]
            [sysrev.article.core :as article]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.db.core :as db :refer
             [do-execute do-query to-jsonb with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.label.core :as label]
            [sysrev.project.core :as project]
            [sysrev.project.description :as description]
            [sysrev.project.member :as member]
            [sysrev.source.core :as source]
            [sysrev.util :refer [in?]]))

(defn copy-project-members [src-project-id dest-project-id &
                            {:keys [admin-members-only? member-user-ids]}]
  (doseq [user-id (project/project-user-ids src-project-id)]
    (when (or (nil? member-user-ids)
              (in? member-user-ids user-id))
      (let [{:keys [permissions]} (member/project-member src-project-id user-id)]
        (when (or (not admin-members-only?)
                  (in? permissions "admin"))
          (member/add-project-member dest-project-id user-id
                                     :permissions permissions))))))

(defn copy-project-label-defs [src-project-id dest-project-id]
  (let [label-definitions (q/find :label {:project-id src-project-id})
        group-labels (->> label-definitions
                          (filter #(= "group" (:value-type %)))
                          (group-by :name))
        sub-labels (filter (comp not nil? :root-label-id-local) label-definitions)
        non-sub-labels (difference (set label-definitions)
                                   (set sub-labels))
        base-label-fn (fn [m]
                        (-> (dissoc m :label-id :label-id-local :project-id)
                            (assoc :project-id dest-project-id)))
        ;;create non-sub-labels
        _ (->>
           non-sub-labels
           (mapv base-label-fn)
           (q/create :label))
        new-group-labels (-> (select :*)
                             (from :label)
                             (where [:and [:= :project-id dest-project-id] [:= :value-type "group"]])
                             db/do-query)
        old-new-label-id-local-map (->> new-group-labels
                                        (map #(hash-map (-> (get group-labels (:name %))
                                                            first
                                                            :label-id-local)
                                                        (:label-id-local %)))
                                        (apply merge))]
    ;; create the sub labels
    (->> sub-labels
         (map base-label-fn)
         (map #(update % :root-label-id-local (fn [n]
                                                (get old-new-label-id-local-map
                                                     n))))
         (q/create :label))))

(defn copy-project-article-labels [src-project-id dest-project-id]
  (with-transaction
    (let [user-ids
          (-> (q/select-project-members dest-project-id [:m.user-id])
              (->> do-query (map :user-id)))
          label-ids-map
          (-> (q/select-label-where
               dest-project-id true [:l.name :l.label-id]
               {:include-disabled? true})
              (->> do-query
                   (map (fn [{:keys [name label-id]}]
                          [name label-id]))
                   (apply concat)
                   (apply hash-map)))
          convert-uuid
          (-> (q/select-project-articles
               dest-project-id [:a.article-uuid :a.parent-article-uuid]
               {:include-disabled? true})
              (->> do-query
                   (map (fn [{:keys [article-uuid parent-article-uuid]}]
                          [parent-article-uuid article-uuid]))
                   (apply concat)
                   (apply hash-map)))
          from-article-id
          (-> (q/select-project-articles
               src-project-id [:a.article-id :a.article-uuid]
               {:include-disabled? true})
              (->> do-query
                   (map (fn [{:keys [article-id article-uuid]}]
                          [article-id article-uuid]))
                   (apply concat)
                   (apply hash-map)))
          to-article-id
          (-> (q/select-project-articles
               dest-project-id [:a.article-id :a.article-uuid]
               {:include-disabled? true})
              (->> do-query
                   (map (fn [{:keys [article-id article-uuid]}]
                          [article-uuid article-id]))
                   (apply concat)
                   (apply hash-map)))
          convert-article-id #(-> % from-article-id convert-uuid to-article-id)
          label-entries
          (-> (q/select-project-article-labels
               src-project-id true [:al.* :l.name :a.article-uuid])
              (->> do-query
                   (filterv #(and (in? user-ids (:user-id %))
                                  ((comp not nil?)
                                   (convert-article-id (:article-id %)))))
                   (mapv (fn [{:keys [article-id name] :as entry}]
                           (-> entry
                               (assoc :article-id (convert-article-id article-id)
                                      :label-id (get label-ids-map name))
                               (dissoc :article-label-local-id
                                       :article-label-id
                                       :article-uuid
                                       :name)
                               (update :answer #(if (nil? %) nil (to-jsonb %))))))))
          note-entries
          (-> (select :an.*)
              (from [:article-note :an])
              (join [:article :a] [:= :a.article-id :an.article-id])
              (where [:= :a.project-id src-project-id])
              (->> do-query
                   (filterv #(and (in? user-ids (:user-id %))
                                  ((comp not nil?)
                                   (convert-article-id (:article-id %)))))
                   (map (fn [{:keys [article-id] :as entry}]
                          (-> entry
                              (assoc :article-id (convert-article-id article-id))
                              (dissoc :article-note-id))))))]
      (log/info (str "copying " (count note-entries) " article-note entries"))
      (doseq [entry-group (partition-all 500 note-entries)]
        (-> (insert-into :article-note)
            (values (vec entry-group))
            do-execute))
      (log/info (str "copying " (count label-entries) " article-label entries"))
      (doseq [entry-group (partition-all 500 label-entries)]
        (-> (insert-into :article-label)
            (values (vec entry-group))
            do-execute)))))

(defn copy-article-source-defs!
  "Given a src-project-id and dest-project-id, create source entries in dest-project-id and return a map of
  {<src-source-id> <dest-source-id>
   ...}"
  [src-project-id dest-project-id]
  (let [src-sources (-> (select :*)
                        (from :project-source)
                        (where [:= :project-id src-project-id])
                        do-query)
        create-source (fn [project-id old-source]
                        (let [new-source (-> (select-keys old-source [:check-new-results :dataset-id :enabled :import-date :import-new-results :meta :new-articles-available :notes])
                                             (assoc :project-id project-id))]
                          (-> (db/with-clear-project-cache project-id
                                (q/create :project-source new-source
                                          :returning [:source-id :meta]))
                              (assoc :src-source-id (:source-id old-source)))))
        dest-sources (map #(create-source dest-project-id %) src-sources)]
    (reduce
     (fn [m {:keys [source-id src-source-id]}]
       (assoc m src-source-id source-id))
     {}
     dest-sources)))

(defn copy-articles!
  "Given a map of src-dest-source-map {<src-source-id> <dest-source-id> ...}, copy the articles from src-project-id to dest-project-id"
  [src-dest-source-map src-project-id dest-project-id]
  (with-transaction
    (let [article-sources-map (source/project-article-sources-map src-project-id)
          project-articles (-> (q/select-project-articles src-project-id [:*] {:include-disabled? true}) do-query)
          src-dest-article-map (->> project-articles
                                    (map (fn [article]
                                           (let [dest-article-id
                                                 (q/create :article (-> article
                                                                        (assoc :project-id dest-project-id)
                                                                        (dissoc :article-id :article-uuid
                                                                                :duplicate-of :parent-article-uuid)
                                                                        (article/article-to-sql))
                                                           :returning :article-id)]
                                             {(:article-id article) dest-article-id})))
                                    (apply merge))
          article-pdfs (-> (select :ap.*)
                           (from [:article_pdf :ap])
                           (join [:article :a] [:= :ap.article_id :a.article_id])
                           (where [:= :a.project-id src-project-id])
                           do-query)]
      (doall (map #(let [sources (second %)]
                     (mapv (fn [source]
                             (q/create :article-source {:source-id (get src-dest-source-map source)
                                                        :article-id (get src-dest-article-map (first %))}))
                           sources))
                  article-sources-map))
      ;; associate article pdfs
      (doall (map #(q/create :article-pdf {:s3-id (:s3-id %)
                                           :article-id (get  src-dest-article-map (:article-id %))})
                  article-pdfs)))))

(defn clone-project
  "Creates a copy of a project.

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords).

  `copy-answers?`, `copy-articles?`, `copy-labels?`, and `copy-members?`
  control whether to copy those entries from the source project.
  If `copy-answers?` is true, `copy-articles?`, `copy-labels?`, and
  `copy-members?` must also be true.

  `admin-members-only?` will skip copying non-admin members to the new project.

  `member-user-ids` (optional) explicitly lists which users to add as members
  of new project."
  [src-project-id &
   {:keys [admin-members-only? copy-answers? copy-articles? copy-labels? copy-members? member-user-ids project-name]
    :or {copy-answers? false, copy-articles? false, copy-labels? false, copy-members? false}}]
  (when (and copy-answers? (not (and copy-articles? copy-labels? copy-members?)))
    (throw (IllegalArgumentException. "copy-articles?, copy-labels?, and copy-members? must be true when copy-answers? is true")))
  (with-transaction
    (let [project-name (or project-name
                           (->
                            (q/find-one :project {:project-id src-project-id})
                            :name
                            str/trim
                            ;; The rules for project names have changed, but old
                            ;; projects still have their original names. This
                            ;; converts them to new-style project names.
                            (str/replace #"[^\w\d]" "-")
                            (str/replace #"^-+" "")
                            (str/replace #"-+$" "")))
          dest-project-id
          (:project-id (project/create-project
                        project-name :parent-project-id src-project-id))]
      (log/info (format "cloning project #%d to (#%d, '%s')"
                        src-project-id dest-project-id project-name))
      (description/set-project-description!
       dest-project-id
       (description/read-project-description src-project-id))
      (when copy-articles?
        (let [src-dest-source-map (copy-article-source-defs! src-project-id dest-project-id)]
          (copy-articles! src-dest-source-map src-project-id dest-project-id)
          (log/info (format "loaded %d articles for project #%d"
                            (project/project-article-count dest-project-id)
                            dest-project-id))))
      (when copy-members?
        (copy-project-members
         src-project-id dest-project-id
         :admin-members-only? admin-members-only?
         :member-user-ids member-user-ids))
      (if-not copy-labels?
        (label/add-label-overall-include dest-project-id)
        (do
          (copy-project-label-defs src-project-id dest-project-id)
          (when copy-answers?
            (copy-project-article-labels src-project-id dest-project-id)
            (predict-api/schedule-predict-update dest-project-id))))
      (log/info (format "finished creating cloned project #%d" dest-project-id))
      dest-project-id)))
