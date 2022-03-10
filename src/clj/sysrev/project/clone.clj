(ns sysrev.project.clone
  (:require [clojure.set :refer [difference]]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [from insert-into join select values
                                               where]]
            [sysrev.article.core :as article]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.db.core :as db :refer
             [do-execute do-query to-jsonb
              with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.file.article :as article-file]
            [sysrev.label.core :as label]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member]
            [sysrev.source.core :as source]
            [sysrev.util :refer [in? index-by]]))

(defn copy-project-members [src-project-id dest-project-id &
                            {:keys [user-ids-only admin-members-only]}]
  (doseq [user-id (project/project-user-ids src-project-id)]
    (when (or (nil? user-ids-only)
              (in? user-ids-only user-id))
      (let [{:keys [permissions]} (member/project-member src-project-id user-id)]
        (when (or (not admin-members-only)
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
          note-id (-> (select :project-note-id)
                      (from [:project-note :pn])
                      (where [:= :project-id dest-project-id])
                      do-query first :project-note-id)
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
                              (assoc :article-id (convert-article-id article-id)
                                     :project-note-id note-id)
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

(defn copy-project-keywords [src-project-id dest-project-id]
  (let [src-id-to-name  (q/find-label {:project-id src-project-id}
                                      :name, :index-by :label-id, :include-disabled true)
        name-to-dest-id (q/find-label {:project-id dest-project-id}
                                      :label-id, :index-by :name, :include-disabled true)
        convert-label-id #(-> % src-id-to-name name-to-dest-id)
        entries (->> (q/find :project-keyword {:project-id src-project-id})
                     (map #(when-let [label-id (convert-label-id (:label-id %))]
                             (-> (dissoc % :keyword-id :label-id :project-id)
                                 (assoc :label-id label-id
                                        :project-id dest-project-id)
                                 (update :label-value to-jsonb))))
                     (filterv some?))]
    (q/create :project-keyword entries)))

(defn populate-child-project-articles [parent-id child-id article-uuids]
  (doseq [article-uuid article-uuids]
    (when-let [article (-> (q/select-article-where
                            parent-id [:= :article-uuid article-uuid] [:*]
                            {:include-disabled? true})
                           do-query first)]
      (when-let [new-article-id
                 (q/create :article (-> article
                                        (assoc :project-id child-id
                                               :parent-article-uuid article-uuid)
                                        (dissoc :article-id :article-uuid
                                                :duplicate-of :text-search)
                                        (article/article-to-sql))
                           :returning :article-id)]
        (doseq [s3-file (article-file/get-article-file-maps (:article-id article))]
          (when (:s3-id s3-file)
            (article-file/associate-article-pdf (:s3-id s3-file) new-article-id)))))))

;; TODO: copy actual sources instead of doing this
(defn create-project-legacy-source
  "Create a new legacy source in project and add all articles to it, if
  project has no sources already."
  [project-id]
  (with-transaction
    (let [n-sources (-> (select :%count.*)
                        (from [:project-source :ps])
                        (where [:= :ps.project-id project-id])
                        do-query first :count)]
      (when (= 0 n-sources)
        (log/info "Creating legacy source entry for project" project-id)
        (let [article-ids (-> (q/select-project-articles
                               project-id [:a.article-id]
                               {:include-disabled? true})
                              (->> do-query (mapv :article-id)))]
          (if (empty? article-ids)
            (log/info "No articles in project")
            (let [source-id (source/create-source
                             project-id
                             (source/make-source-meta :legacy {}))]
              (log/info "Creating" (count article-ids)
                        "article source entries for project" project-id)
              (doseq [ids-group (partition-all 200 article-ids)]
                (source/add-articles-to-source ids-group source-id)))))))))

;; TODO: should copy "Project Documents" files
;; TODO: should copy project sources (not just articles)
(defn clone-project
  "Creates a copy of a project.

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords).

  `articles`, `labels`, `answers`, `members` control whether to copy
  those entries from the source project.

  `admin-members-only` if true will skip copying non-admin members to
  new project.

  `user-ids-only` (optional) explicitly lists which users to add as members
  of new project."
  [project-name src-id &
   {:keys [articles labels answers members user-ids-only admin-members-only]
    :or {labels false, answers false, articles false, members true}}]
  (with-transaction
    (let [dest-id
          (:project-id (project/create-project
                        project-name :parent-project-id src-id))
          article-uuids
          (when articles
            (-> (q/select-project-articles src-id [:a.article-uuid])
                (->> do-query (map :article-uuid))))]
      (project/add-project-note dest-id {})
      (log/info (format "created project (#%d, '%s')"
                        dest-id project-name))
      (when articles
        (populate-child-project-articles
         src-id dest-id article-uuids)
        (log/info (format "loaded %d articles"
                          (project/project-article-count dest-id))))
      (when members
        (copy-project-members src-id dest-id
                              :user-ids-only user-ids-only
                              :admin-members-only admin-members-only))
      (if labels
        (copy-project-label-defs src-id dest-id)
        (label/add-label-overall-include dest-id))
      (when labels
        (copy-project-keywords src-id dest-id))
      (when (and labels answers articles)
        (copy-project-article-labels src-id dest-id))
      (log/info "clone-project done")
      (create-project-legacy-source dest-id)
      (when (and articles answers)
        (predict-api/schedule-predict-update dest-id))
      dest-id)))

(defn copy-article-source-defs!
  "Given a src-project-id and dest-project-id, create source entries in dest-project-id and return a map of
  {<src-source-id> <dest-source-id>
   ...}"
  [src-project-id dest-project-id]
  (let [src-sources (-> (select :meta :enabled :source-id)
                        (from :project-source)
                        (where [:= :project-id src-project-id])
                        do-query)
        create-source (fn [project-id metadata]
                        (db/with-clear-project-cache project-id
                          (q/create :project-source {:project-id project-id :meta metadata}
                                    :returning [:source-id :meta])))
        dest-sources (map #(create-source dest-project-id (:meta %)) src-sources)
        src-sources-index (index-by :meta src-sources)]
    (->> dest-sources
         (map (fn [source]
                {:src-source-id (-> (get src-sources-index
                                         (:meta source))
                                    :source-id)
                 :dest-source-id (:source-id source)}))
         (map #(hash-map (:src-source-id %) (:dest-source-id %)))
         (apply merge))))

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
