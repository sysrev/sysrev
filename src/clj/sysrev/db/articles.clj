(ns sysrev.db.articles
  (:require
   [clojure.spec :as s]
   [clojure.java.jdbc :as j]
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

(defn add-article [article project-id]
  (let [project-id (q/to-project-id project-id)]
    (-> (insert-into :article)
        (values [(merge (article-to-sql article)
                        {:project-id project-id})])
        (returning :article-id)
        do-query first :article-id)))
;;
(s/fdef add-article
        :args (s/cat :article ::sa/article-partial
                     :project-id ::sc/project-id)
        :ret (s/nilable ::sc/article-id))

(defn set-user-article-note [article-id user-id note-name content]
  (let [article-id (q/to-article-id article-id)
        user-id (q/to-user-id user-id)
        note-name (name note-name)
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
                     :note-name (s/or :s string?
                                      :k keyword?)
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

(defn query-article-by-id-full
  "Queries for an article ID with data from other tables included."
  [article-id & [{:keys [predict-run-id include-disabled?]
                  :or {include-disabled? false}}]]
  (let [[article score locations review-status]
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
         (article-review-status article-id))]
    (when (not-empty article)
      (-> article
          (assoc :locations locations)
          (assoc :score (or score 0.0))
          (assoc :review-status review-status)))))

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
