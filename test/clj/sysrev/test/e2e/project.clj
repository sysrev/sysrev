(ns sysrev.test.e2e.project
  (:require [etaoin.api :as ea]
            [sysrev.etaoin-test.interface :as et]
            [sysrev.test.e2e.core :as e]))

(defn create-project! [{:keys [driver] :as test-resources} name]
  (e/go test-resources "/new")
  (doto driver
    (ea/wait-visible {:css "#create-project .project-name input"})
    (ea/fill {:css "#create-project .project-name input"} name)
    (et/click-visible "//button[contains(text(),'Create Project')]")
    e/wait-until-loading-completes))
