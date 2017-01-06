(ns sysrev-web.user
  (:require sysrev-web.base
            sysrev-web.util
            sysrev-web.state.core
            sysrev-web.state.data
            sysrev-web.ajax
            sysrev-web.notify
            sysrev-web.main
            sysrev-web.routes
            sysrev-web.ui.core
            sysrev-web.ui.components
            sysrev-web.ui.article
            sysrev-web.ui.article-page
            sysrev-web.ui.labels
            sysrev-web.ui.password-reset
            sysrev-web.ui.sysrev
            sysrev-web.ui.login
            sysrev-web.ui.user-profile
            sysrev-web.ui.users
            sysrev-web.ui.select-project
            sysrev-web.ui.classify
            [clojure.string :as str])
  (:require-macros [sysrev-web.macros :refer [import-vars]]))

(defn populate-user-ns []
  (import-vars 'sysrev-web.base)
  (import-vars 'sysrev-web.util)
  (import-vars 'sysrev-web.state.core)
  (import-vars 'sysrev-web.state.data)
  (import-vars 'sysrev-web.ajax)
  (import-vars 'sysrev-web.notify)
  (import-vars 'sysrev-web.main)
  (import-vars 'sysrev-web.routes)
  (import-vars 'sysrev-web.ui.core)
  (import-vars 'sysrev-web.ui.components)
  (import-vars 'sysrev-web.ui.article)
  (import-vars 'sysrev-web.ui.article-page)
  (import-vars 'sysrev-web.ui.labels)
  (import-vars 'sysrev-web.ui.password-reset)
  (import-vars 'sysrev-web.ui.sysrev)
  (import-vars 'sysrev-web.ui.login)
  (import-vars 'sysrev-web.ui.user-profile)
  (import-vars 'sysrev-web.ui.users)
  (import-vars 'sysrev-web.ui.select-project)
  (import-vars 'sysrev-web.ui.classify)
  true)

(populate-user-ns)
