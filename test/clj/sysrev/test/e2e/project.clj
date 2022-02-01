(ns sysrev.test.e2e.project
  (:require [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.gengroup.core :as gengroup]
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
      project-id)))

(defn create-project-member-gengroup! [{:keys [driver prefer-browser?] :as test-resources} project-id gengroup-name gengroup-description]
  (if prefer-browser?
    (do
      (e/go-project test-resources project-id "/users")
      (doto driver
        (et/click-visible :new-gengroup-btn)
        (et/fill-visible :gengroup-name-input gengroup-name)
        (et/fill-visible :gengroup-description-input gengroup-description)
        (et/click-visible :create-gengroup-btn)
        (ea/wait-visible {:css ".alert-message.success"})))
    (gengroup/create-project-member-gengroup! project-id gengroup-name gengroup-description))
  nil)
