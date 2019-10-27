(ns sysrev.project.description
  (:require [sysrev.db.core :as db]
            [sysrev.db.queries :as q]))

(defn project-description-markdown-id [project-id]
  (first (q/find :project-description {:project-id project-id} :markdown-id)))

(defn create-markdown-entry [md-string]
  (q/create :markdown {:string md-string}, :returning :markdown-id))

(defn set-project-description!
  "Sets value for a project description."
  [project-id md-string]
  (db/with-clear-project-cache project-id
    (let [markdown-id (project-description-markdown-id project-id)]
      (cond (empty? md-string)
            ;; delete entry
            (when markdown-id
              (q/delete :markdown {:markdown-id markdown-id})
              (q/delete :project-description {:project-id project-id
                                              :markdown-id markdown-id}))

            (integer? markdown-id)
            ;; update entry
            (q/modify :markdown {:markdown-id markdown-id} {:string md-string})

            :else
            ;; create entry
            (let [markdown-id (create-markdown-entry md-string)]
              (q/create :project-description {:project-id project-id
                                              :markdown-id markdown-id}))))))

(defn read-project-description
  "Returns the markdown string for description of `project-id`."
  [project-id]
  (first (q/find [:project-description :pd] {:pd.project-id project-id}
                 :md.string, :join [:markdown:md :pd.markdown-id])))
