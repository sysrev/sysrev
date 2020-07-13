(ns sysrev.views.project)

(defn ProjectName [project project-owner]
  (str (when (seq project-owner) (str (:name project-owner) "/")) (:name project)))
