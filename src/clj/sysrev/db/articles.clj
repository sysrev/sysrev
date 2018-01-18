(ns sysrev.db.articles
  (:require [clojure.spec.alpha :as s]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [sysrev.shared.util :as u]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.db.core :as db :refer
             [do-query do-execute to-sql-array sql-now with-project-cache
              with-query-cache clear-project-cache to-jsonb]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.queries :as q]))

(defn article-to-sql
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article & [conn]]
  (-> article
      (update :authors #(to-sql-array "text" % conn))
      (update :keywords #(to-sql-array "text" % conn))
      (update :urls #(to-sql-array "text" % conn))
      (update :document-ids #(to-sql-array "text" % conn))))
;;
(s/fdef article-to-sql
        :args (s/cat :article ::sa/article-partial
                     :conn (s/? any?))
        :ret map?)

(defn add-article [article project-id & [conn]]
  (let [project-id (q/to-project-id project-id)]
    (-> (insert-into :article)
        (values [(merge (article-to-sql article conn)
                        {:project-id project-id})])
        (returning :article-id)
        do-query first :article-id)))
;;
(s/fdef add-article
        :args (s/cat :article ::sa/article-partial
                     :project-id ::sc/project-id
                     :conn (s/? any?))
        :ret (s/nilable ::sc/article-id))

(defn set-user-article-note [article-id user-id note-name content]
  (let [article-id (q/to-article-id article-id)
        user-id (q/to-user-id user-id)
        pnote (-> (q/select-article-by-id article-id [:pn.*])
                  (merge-join [:project :p]
                              [:= :p.project-id :a.project-id])
                  (q/with-project-note note-name)
                  do-query first)
        anote (-> (q/select-article-by-id article-id [:an.*])
                  (q/with-article-note note-name user-id)
                  do-query first)]
    (assert pnote "note type not defined in project")
    (assert (:project-id pnote) "project-id not found")
    (clear-project-cache (:project-id pnote) [:article article-id :notes])
    (clear-project-cache (:project-id pnote) [:users user-id :notes])
    (let [fields {:article-id article-id
                  :user-id user-id
                  :project-note-id (:project-note-id pnote)
                  :content content
                  :updated-time (sql-now)}]
      (if (nil? anote)
        (-> (sqlh/insert-into :article-note)
            (values [fields])
            (returning :*)
            do-query)
        (-> (sqlh/update :article-note)
            (where [:and
                    [:= :article-id article-id]
                    [:= :user-id user-id]
                    [:= :project-note-id (:project-note-id pnote)]])
            (sset fields)
            (returning :*)
            do-query)))))
;;
(s/fdef set-user-article-note
        :args (s/cat :article-id ::sc/article-id
                     :user-id ::sc/user-id
                     :note-name string?
                     :content (s/nilable string?))
        :ret (s/nilable map?))

(defn article-user-notes-map [project-id article-id]
  (let [project-id (q/to-project-id project-id)
        article-id (q/to-article-id article-id)]
    (with-project-cache
      project-id [:article article-id :notes :user-notes-map]
      (->>
       (-> (q/select-article-by-id article-id [:an.* :pn.name])
           (q/with-article-note)
           do-query)
       (group-by :user-id)
       (u/map-values
        #(->> %
              (group-by :name)
              (u/map-values first)
              (u/map-values :content)))))))
;;
(s/fdef article-user-notes-map
        :args (s/cat :project-id ::sc/project-id
                     :article-id ::sc/article-id)
        :ret (s/nilable map?))

(defn remove-article-flag [article-id flag-name]
  (-> (delete-from :article-flag)
      (where [:and
              [:= :article-id article-id]
              [:= :flag-name flag-name]])
      do-execute))
;;
(s/fdef remove-article-flag
        :args (s/cat :article-id ::sc/article-id
                     :flag-name ::sa/flag-name)
        :ret ::sc/sql-execute)

(defn set-article-flag [article-id flag-name disable? & [meta]]
  (remove-article-flag article-id flag-name)
  (-> (insert-into :article-flag)
      (values [{:article-id article-id
                :flag-name flag-name
                :disable disable?
                :meta (when meta (to-jsonb meta))}])
      (returning :*)
      do-query first))
;;
(s/fdef set-article-flag
        :args (s/cat :article-id ::sc/article-id
                     :flag-name ::sa/flag-name
                     :disable? boolean?
                     :meta (s/? ::sa/meta))
        :ret map?)

(defn article-review-status [article-id]
  (let [entries
        (-> (q/select-article-by-id
             article-id [:al.user-id :al.resolve :al.answer])
            (q/join-article-labels)
            (q/join-article-label-defs)
            (q/filter-valid-article-label true)
            (q/filter-overall-label)
            do-query)]
    (cond
      (empty? entries) :unreviewed
      (some :resolve entries) :resolved
      (= 1 (count entries)) :single
      (= 1 (->> entries (map :answer) distinct count)) :consistent
      :else :conflict)))

(defn find-article-flags-by-id [article-id]
  (->> (-> (select :flag-name :disable :date-created :meta)
           (from [:article-flag :aflag])
           (where [:= :article-id article-id])
           do-query)
       (group-by :flag-name)
       (u/map-values first)
       (u/map-values #(dissoc % :flag-name))))

(defn query-article-by-id-full
  "Queries for an article ID with data from other tables included."
  [article-id & [{:keys [predict-run-id include-disabled?]
                  :or {include-disabled? false}}]]
  (let [[article score locations review-status flags]
        (pvalues
         (-> (q/select-article-by-id
              article-id [:a.*] {:include-disabled? include-disabled?})
             do-query first)
         (-> (q/select-article-by-id
              article-id [:a.article-id] {:include-disabled? include-disabled?})
             (q/with-article-predict-score
               (or predict-run-id
                   (q/article-latest-predict-run-id article-id)))
             do-query first :score)
         (->> (q/query-article-locations-by-id
               article-id [:al.source :al.external-id])
              (group-by :source))
         (article-review-status article-id)
         (find-article-flags-by-id article-id))]
    (when (not-empty article)
      (-> article
          (assoc :locations locations)
          (assoc :score (or score 0.0))
          (assoc :review-status review-status)
          (assoc :flags flags)))))

(defn find-project-article-by-uuid
  "Query for an article entry in a project by matching on either
  `article-uuid` or `parent-article-uuid`."
  [project-id article-uuid &
   {:keys [fields include-disabled?]
    :or {fields [:*]
         include-disabled? true}}]
  (-> (q/select-article-where
       project-id
       [:or
        [:= :a.article-uuid article-uuid]
        [:= :a.parent-article-uuid article-uuid]]
       fields
       {:include-disabled? include-disabled?})
      do-query
      first))

(defn flag-project-articles-by-uuid
  [project-id article-uuids flag-name disable? & [meta]]
  (doseq [article-uuid article-uuids]
    (let [{:keys [article-id]}
          (find-project-article-by-uuid
           project-id article-uuid :fields [:a.article-id])]
      (if (nil? article-id)
        (log/warn (format "article not found: %s" article-uuid))
        (set-article-flag
         article-id flag-name disable? meta)))))

(defn to-article
  "Queries by id argument or returns article map argument unmodified."
  [article-or-id]
  (let [in (s/conform ::sa/article-or-id article-or-id)]
    (if (= in ::s/invalid)
      nil
      (let [[t v] in]
        (cond
          (= t :map) v
          (= t :id) (let [[_ id] v]
                      (->> (query-article-by-id-full id)
                           (s/assert (s/nilable ::sa/article))))
          :else nil)))))
;;
(s/fdef to-article
        :args (s/cat :article-or-id ::sa/article-or-id)
        :ret (s/nilable ::sa/article))

(defn copy-project-article [src-project-id dest-project-id article-id]
  (try
    (if-let [article (query-article-by-id-full article-id)]
      (if (= (:project-id article) src-project-id)
        (if-let [new-article-id
                 (add-article (-> article (dissoc :article-id
                                                  :article-uuid
                                                  :project-id
                                                  :duplicate-of
                                                  :locations
                                                  :review-status
                                                  :score
                                                  :flags))
                              dest-project-id)]
          (let [locations
                (-> (q/select-project-articles
                     src-project-id [:aloc.article-id :aloc.source :aloc.external-id])
                    (q/join-article-locations)
                    (merge-where [:= :aloc.article-id article-id])
                    (->> do-query
                         (mapv #(assoc % :article-id new-article-id))))]
            (when-not (empty? locations)
              (-> (sqlh/insert-into :article-location)
                  (values (vec locations))
                  do-execute))
            :success)
          :add-article-failed)
        :wrong-article-project)
      :not-found)
    (catch Throwable e
      :error)
    (finally
      (when dest-project-id
        (clear-project-cache dest-project-id)))))

(defn add-article-to-source!
  "Add article-id to source-id"
  [article-id source-id]
  (-> (sqlh/insert-into :article_source)
      (values [{:article_id article-id
                :source_id source-id}])
      do-execute))

(defn articles-have-labels?
  "Does the coll of article ids have labels associated with them?"
  [coll]
  (boolean (> (-> (select :%count.*)
                  (from :article-label)
                  (where [:in :article_id coll])
                  do-query
                  first
                  :count)
              0)))

(s/fdef articles-have-labels?
        :args (s/cat :coll coll?)
        :ret boolean?)
