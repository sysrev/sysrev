(ns sysrev.clone-project
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-jsonb]]
            [sysrev.db.project :as project]
            [sysrev.db.articles :as articles]
            [sysrev.db.labels :as labels]
            [sysrev.db.documents :as docs]
            [sysrev.db.queries :as q]
            [sysrev.import.endnote :as endnote]
            [sysrev.shared.util :refer [in?]]))

(defn copy-project-members [src-project-id dest-project-id &
                            {:keys [user-ids-only admin-members-only]}]
  (doseq [user-id (project/project-user-ids src-project-id)]
    (when (or (nil? user-ids-only)
              (in? user-ids-only user-id))
      (let [{:keys [permissions]} (project/project-member src-project-id user-id)]
        (when (or (not admin-members-only)
                  (in? permissions "admin"))
          (project/add-project-member dest-project-id user-id
                                      :permissions permissions))))))

(defn copy-project-label-defs [src-project-id dest-project-id]
  (let [entries
        (-> (select :*)
            (from [:label :l])
            (where [:= :project-id src-project-id])
            (->> do-query
                 (mapv #(-> %
                            (dissoc :label-id :label-id-local :project-id)
                            (assoc :project-id dest-project-id)))))]
    (-> (insert-into :label)
        (values entries)
        do-execute)))

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
                                      :confirm-time nil
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
      (println (str "copying " (count note-entries) " article-note entries"))
      (doseq [entry-group (partition-all 500 note-entries)]
        (-> (insert-into :article-note)
            (values (vec entry-group))
            do-execute))
      (println (str "copying " (count label-entries) " article-label entries"))
      (doseq [entry-group (partition-all 500 label-entries)]
        (-> (insert-into :article-label)
            (values (vec entry-group))
            do-execute)))))

(defn copy-project-keywords [src-project-id dest-project-id]
  (let [src-id-to-name
        (-> (q/select-label-where
             src-project-id true
             [:label-id :name]
             {:include-disabled? true})
            (->> do-query
                 (map (fn [{:keys [label-id name]}]
                        [label-id name]))
                 (apply concat)
                 (apply hash-map)))
        name-to-dest-id
        (-> (-> (q/select-label-where
                 dest-project-id true
                 [:label-id :name]
                 {:include-disabled? true})
                (->> do-query
                     (map (fn [{:keys [label-id name]}]
                            [name label-id]))
                     (apply concat)
                     (apply hash-map))))
        convert-label-id
        #(-> % src-id-to-name name-to-dest-id)
        entries
        (-> (q/select-project-keywords src-project-id [:*])
            (->> do-query
                 (map #(when-let [label-id (convert-label-id (:label-id %))]
                         (-> %
                             (dissoc :keyword-id :label-id :project-id)
                             (assoc :label-id label-id
                                    :project-id dest-project-id)
                             (update :label-value to-jsonb))))
                 (remove nil?)
                 vec))]
    (when-not (empty? entries)
      (-> (insert-into :project-keyword)
          (values entries)
          do-execute))))

(defn populate-child-project-articles [parent-id child-id article-uuids]
  (doseq [article-uuid article-uuids]
    (when-let [article (-> (q/select-article-where
                            parent-id [:= :article-uuid article-uuid] [:*]
                            {:include-disabled? true})
                           do-query first)]
      (-> (insert-into :article)
          (values [(-> article
                       (assoc :project-id child-id
                              :parent-article-uuid article-uuid)
                       (dissoc :article-id
                               :article-uuid
                               :duplicate-of)
                       (articles/article-to-sql))])
          do-execute))))

;; TODO: should copy "Project Documents" files
;; TODO: should copy project sources (not just articles)
(defn clone-project
  "Creates a copy of a project.

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords)."
  [project-name src-id &
   {:keys [user-ids-only admin-members-only labels? answers?]
    :or {labels? false answers? false}}]
  (with-transaction
    (let [dest-id
          (:project-id (project/create-project
                        project-name :parent-project-id src-id))
          article-uuids
          (-> (q/select-project-articles src-id [:a.article-uuid])
              (->> do-query (map :article-uuid)))]
      (project/add-project-note dest-id {})
      (println (format "created project (#%d, '%s')"
                       dest-id project-name))
      (populate-child-project-articles
       src-id dest-id article-uuids)
      (println (format "loaded %d articles"
                       (project/project-article-count dest-id)))
      (copy-project-members src-id dest-id
                            :user-ids-only user-ids-only
                            :admin-members-only admin-members-only)
      (if labels?
        (copy-project-label-defs src-id dest-id)
        (labels/add-label-overall-include dest-id))
      (when labels?
        (copy-project-keywords src-id dest-id))
      (when (and labels? answers?)
        (copy-project-article-labels src-id dest-id))))
  (println "clone-project done"))

(defn clone-subproject-articles
  "Creates a copy of a project with a subset of the articles from a parent project.

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords)."
  [project-name src-id article-uuids &
   {:keys [user-ids-only admin-members-only labels? answers?]
    :or {labels? false answers? false}}]
  (with-transaction
    (let [dest-id
          (:project-id (project/create-project
                        project-name :parent-project-id src-id))]
      (project/add-project-note dest-id {})
      (println (format "created project (#%d, '%s')"
                       dest-id project-name))
      (populate-child-project-articles
       src-id dest-id article-uuids)
      (println (format "loaded %d articles"
                       (project/project-article-count dest-id)))
      (copy-project-members src-id dest-id
                            :user-ids-only user-ids-only
                            :admin-members-only admin-members-only)
      (if labels?
        (copy-project-label-defs src-id dest-id)
        (labels/add-label-overall-include dest-id))
      (when labels?
        (copy-project-keywords src-id dest-id))
      (when (and labels? answers?)
        (copy-project-article-labels src-id dest-id))))
  (println "clone-subproject-articles done"))

(defn clone-subproject-endnote
  "Clones a project from the subset of articles in `parent-id` project that
  are contained in Endnote XML export file `endnote-path`. Also imports
  `document` entries using file `endnote-path` and directory `pdfs-path`,
  and attaches `document-id` values to article entries.

  The `endnote-path` file must be in the format created by
  `filter-endnote-xml-includes` (has the original `article-uuid` values
  attached to each Endnote article entry under field `:custom5`).

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords).

  The `project` and `article` entries will reference the parent project using
  fields `parent-project-id` and `parent-article-uuid`."
  [project-name parent-id endnote-path pdfs-path]
  (with-transaction
    (let [article-doc-ids (endnote/load-endnote-doc-ids endnote-path)
          article-uuids (keys article-doc-ids)
          child-id
          (:project-id (project/create-project
                        project-name :parent-project-id parent-id))]
      (project/add-project-note child-id {})
      (println (format "created child project (#%d, '%s')"
                       child-id project-name))
      (populate-child-project-articles
       parent-id child-id article-uuids)
      (println (format "loaded %d articles"
                       (project/project-article-count child-id)))
      (copy-project-members parent-id child-id)
      (docs/load-article-documents child-id pdfs-path)
      (docs/load-project-document-ids child-id article-doc-ids)
      (copy-project-label-defs parent-id child-id)
      (copy-project-keywords parent-id child-id)
      (println "clone-subproject-endnote done"))))