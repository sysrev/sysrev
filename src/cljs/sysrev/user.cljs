(ns sysrev.user
  (:require sysrev.base
            sysrev.core
            sysrev.util
            sysrev.routes
            sysrev.ajax
            sysrev.nav
            sysrev.state.all
            sysrev.data.core
            sysrev.loading
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
            [clojure.spec.alpha :as s]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            [cognitect.transit :as transit])
  (:require-macros [sysrev.macros :refer [import-vars]]))

(defn populate-user-ns []
  (import-vars 'sysrev.base)
  (import-vars 'sysrev.core)
  (import-vars 'sysrev.util)
  (import-vars 'sysrev.routes)
  (import-vars 'sysrev.state.core)
  (import-vars 'sysrev.state.ui)
  (import-vars 'sysrev.data.core)
  (import-vars 'sysrev.views.base)
  (import-vars 'sysrev.views.main)
  (import-vars 'sysrev.shared.util)
  (import-vars 'sysrev.shared.keywords)
  (import-vars 'sysrev.shared.spec.core)
  (import-vars 'sysrev.shared.spec.article)
  (import-vars 'sysrev.nav)
  (import-vars 'sysrev.loading)
  true)

(populate-user-ns)
