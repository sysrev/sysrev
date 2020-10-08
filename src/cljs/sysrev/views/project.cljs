(ns sysrev.views.project)

(defn ProjectName [project-name owner-name]
  (str (some-> owner-name (str "/"))
       project-name))
