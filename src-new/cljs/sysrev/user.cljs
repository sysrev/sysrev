(ns sysrev.user
  (:require sysrev.base
            sysrev.core
            sysrev.util
            sysrev.routes
            sysrev.fx
            sysrev.events.all
            sysrev.subs.all
            sysrev.data.core
            sysrev.data.definitions
            sysrev.views.main
            sysrev.shared.util
            sysrev.shared.keywords
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.users :as su]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.notes :as snt]
            [sysrev.spec.core :as csc]
            [sysrev.spec.db :as csd]
            [sysrev.spec.identity :as csi]
            [clojure.string :as str]
            [cljs-time.core :as t]
            [clojure.spec :as s]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]])
  (:require-macros [sysrev.macros :refer [import-vars]]))

(defn populate-user-ns []
  (import-vars 'sysrev.base)
  (import-vars 'sysrev.core)
  (import-vars 'sysrev.util)
  (import-vars 'sysrev.routes)
  (import-vars 'sysrev.fx)
  (import-vars 'sysrev.subs.core)
  (import-vars 'sysrev.data.core)
  (import-vars 'sysrev.views.main)
  (import-vars 'sysrev.shared.util)
  (import-vars 'sysrev.shared.keywords)
  (import-vars 'sysrev.shared.spec.core)
  (import-vars 'sysrev.shared.spec.article)
  true)

(populate-user-ns)
