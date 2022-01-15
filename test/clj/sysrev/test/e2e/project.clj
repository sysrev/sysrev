(ns sysrev.test.e2e.project
  (:require [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.label.core :as label]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member]
            [sysrev.test.e2e.core :as e]))

(defn current-project-id [driver]
  (let [[_ id-str] (re-matches #".*/p/(\d+)/?.*" (e/get-path driver))]
    (some-> id-str Long/parseLong)))

(defn create-project! [{:keys [driver prefer-browser?] :as test-resources} name]
  (if prefer-browser?
    (do
      (e/go test-resources "/new")
      (doto driver
        (ea/wait-visible {:css "#create-project .project-name input"})
        (ea/fill {:css "#create-project .project-name input"} name)
        (et/click-visible "//button[contains(text(),'Create Project')]")
        e/wait-until-loading-completes)
      (current-project-id driver))
    (let [owner-id (e/current-user-id driver)
          {:keys [project-id]} (project/create-project name)]
      (label/add-label-overall-include project-id)
      (member/add-project-member
       project-id owner-id
       :permissions ["owner" "admin" "member"])
      (e/go test-resources (str "/p/" project-id))
      (e/wait-until-loading-completes driver)
      project-id)))

