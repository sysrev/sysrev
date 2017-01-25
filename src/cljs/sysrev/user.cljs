(ns sysrev.user
  (:require sysrev.base
            sysrev.util
            sysrev.state.core
            sysrev.state.data
            sysrev.ajax
            sysrev.notify
            sysrev.main
            sysrev.routes
            sysrev.ui.core
            sysrev.ui.components
            sysrev.ui.article
            sysrev.ui.article-page
            sysrev.ui.labels
            sysrev.ui.password-reset
            sysrev.ui.sysrev
            sysrev.ui.login
            sysrev.ui.user-profile
            sysrev.ui.users
            sysrev.ui.select-project
            sysrev.ui.classify
            [clojure.string :as str])
  (:require-macros [sysrev.macros :refer [import-vars]]))

(defn populate-user-ns []
  (import-vars 'sysrev.base)
  (import-vars 'sysrev.util)
  (import-vars 'sysrev.state.core)
  (import-vars 'sysrev.state.data)
  (import-vars 'sysrev.ajax)
  (import-vars 'sysrev.notify)
  (import-vars 'sysrev.main)
  (import-vars 'sysrev.routes)
  (import-vars 'sysrev.ui.core)
  (import-vars 'sysrev.ui.components)
  (import-vars 'sysrev.ui.article)
  (import-vars 'sysrev.ui.article-page)
  (import-vars 'sysrev.ui.labels)
  (import-vars 'sysrev.ui.password-reset)
  (import-vars 'sysrev.ui.sysrev)
  (import-vars 'sysrev.ui.login)
  (import-vars 'sysrev.ui.user-profile)
  (import-vars 'sysrev.ui.users)
  (import-vars 'sysrev.ui.select-project)
  (import-vars 'sysrev.ui.classify)
  true)

(populate-user-ns)
