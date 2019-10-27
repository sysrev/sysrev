(ns sysrev.label.migrate
  (:require [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.label.answer :as answer]
            [sysrev.shared.labels :refer [sort-project-labels]]
            [sysrev.shared.util :refer [index-by]]))

;;;
;;; article_resolve migration
;;;

(defn project-resolved-articles
  "(DB format migration) Get sequence of article ids in project that have
  resolved answers based on old-format article_label entries."
  [project-id]
  (q/find [:article :a] {:a.project-id project-id :resolve true}
          [[:%distinct.a.article-id :article-id]]
          :join [:article-label:al :a.article-id]))

(defn article-resolve-info
  "(DB format migration) Get map of resolved answer status for
  article-id based on old-format article_label entries."
  [article-id]
  (first (q/find :article-label {:article-id article-id :resolve true}
                 [:article-id :user-id :updated-time]
                 :order-by [:updated-time :desc] :limit 1)))

(defn project-has-article-resolve?
  "(DB format migration) Test for presence of new-format article_resolve
  entries in project."
  [project-id]
  (pos-int? (q/find-one [:article-resolve :aresolve] {:a.project-id project-id}
                        [[:%count.%distinct.aresolve.article-id :count]]
                        :join [:article:a :aresolve.article-id])))

(defn migrate-project-article-resolve
  "Create article_resolve entries in project-id using old-format values
  from article_label."
  [project-id]
  (db/with-clear-project-cache project-id
    (when-not (project-has-article-resolve? project-id)
      (log/infof "migrating article resolve for project #%d" project-id)
      (let [article-ids (try (project-resolved-articles project-id)
                             (catch Throwable _ (log/info "unable to query on article.resolve")))]
        (doseq [article-id article-ids]
          (when-let [{:keys [user-id updated-time]} (article-resolve-info article-id)]
            (answer/resolve-article-answers article-id user-id :resolve-time updated-time)))))))

(defn projects-with-resolve
  "(DB format migration) Get project ids with resolved articles based on
  old-format article_label entries."
  []
  (vec (sort (q/find [:article-label :al] {:al.resolve true}
                     [[:%distinct.a.project-id :project-id]]
                     :join [:article:a :al.article-id]))))

(defn migrate-all-project-article-resolve
  "Create article_resolve entries for all projects."
  []
  (db/with-transaction
    (doseq [project-id (projects-with-resolve)]
      (migrate-project-article-resolve project-id))))

(defn migrate-labels-project-ordering
  "Set label.project_ordering values to match legacy label ordering logic.
  Should only be run manually one time to avoid reverting new and
  reordered labels."
  []
  (doseq [project-id (project/project-ids-where-labels-defined)]
    (db/with-clear-project-cache project-id
      (let [labels (q/find :label {:project-id project-id})
            labels-enabled (filter :enabled labels)
            enabled-ids-sorted (sort-project-labels (index-by :label-id labels-enabled))
            disabled-ids (mapv :label-id (remove :enabled labels))]
        (log/infof "migrating label ordering for project #%d (%d+%d labels)"
                   project-id (count enabled-ids-sorted) (count disabled-ids))
        (doseq [[i label-id] (map-indexed vector enabled-ids-sorted)]
          (q/modify :label {:label-id label-id} {:project-ordering i}))
        (when (seq disabled-ids)
          (q/modify :label {:label-id disabled-ids} {:project-ordering nil}))))))

;; TODO: do this later maybe
;;
;; note: this was related to identifying categorical label values
;;       using a uuid rather than directly by their text value
#_
(defn ^:unused migrate-label-uuid-values [label-id]
  (db/with-transaction
    (let [{:keys [value-type definition]}
          (-> (select :value-type :definition)
              (from :label)
              (where [:= :label-id label-id])
              do-query first)
          {:keys [all-values inclusion-values]} definition]
      (when (and (= value-type "categorical") (vector? all-values))
        (let [new-values (->> all-values
                              (map (fn [v] {(UUID/randomUUID) {:name v}}))
                              (apply merge))
              to-uuid (fn [v]
                        (->> (keys new-values)
                             (filter #(= v (get-in new-values [% :name])))
                             first))
              new-inclusion (->> inclusion-values (map to-uuid) (remove nil?) vec)
              al-entries (-> (select :article-label-id :answer)
                             (from :article-label)
                             (where [:= :label-id label-id])
                             do-query)]
          (doseq [{:keys [article-label-id answer]} al-entries]
            (-> (sqlh/update :article-label)
                (sset {:answer (to-jsonb (some->> answer (mapv to-uuid)))})
                (where [:= :article-label-id article-label-id])
                do-execute))
          (-> (sqlh/update :label)
              (sset {:definition
                     (to-jsonb
                      (assoc definition
                             :all-values new-values
                             :inclusion-values new-inclusion))})
              (where [:= :label-id label-id])
              do-execute))))))
