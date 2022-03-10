(ns sysrev.label.migrate
  (:require [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.label.answer :as answer]))

;;;
;;; article_resolve migration
;;;

(defn project-resolved-articles
  "(DB format migration) Get sequence of article ids in project that have
  resolved answers based on old-format article_label entries."
  [project-id]
  (q/find [:article :a] {:a.project-id project-id :resolve true}
          [[:%distinct.a.article-id :article-id]]
          :join [[:article-label :al] :a.article-id]))

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
                        :join [[:article :a] :aresolve.article-id])))

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
                     :join [[:article :a] :al.article-id]))))

(defn migrate-all-project-article-resolve
  "Create article_resolve entries for all projects."
  []
  (db/with-transaction
    (doseq [project-id (projects-with-resolve)]
      (migrate-project-article-resolve project-id))))
