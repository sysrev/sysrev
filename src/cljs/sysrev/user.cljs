#_{:clj-kondo/ignore [:unused-import :unused-namespace :unused-referred-var :use :refer-all]}
(ns sysrev.user
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [orchestra-cljs.spec.test :as spec-test]
            [cljs-time.core :as t]
            [cljs-http.client :as http]
            [cognitect.transit :as transit]
            [re-frame.core :refer [subscribe dispatch]]
            [re-frame.db :refer [app-db]]
            ["chart.js" :as Chart]
            ["pdfjs-dist" :as pdfjs]
            ["pdfjs-dist/web/pdf_viewer" :as pdfjsViewer]
            ["jquery" :as $]
            sysrev.base
            sysrev.core
            sysrev.util
            sysrev.ajax
            sysrev.nav
            sysrev.state.all
            [sysrev.action.core :as action]
            [sysrev.data.core :as data]
            sysrev.loading
            sysrev.views.main
            sysrev.shared.keywords
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.users :as su]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.notes :as snt]
            [sysrev.macros :refer-macros [import-vars]]))

(defn populate-user-ns []
  (import-vars 'sysrev.base)
  (import-vars 'sysrev.core)
  (import-vars 'sysrev.util)
  (import-vars 'sysrev.state.core)
  (import-vars 'sysrev.state.ui)
  (import-vars 'sysrev.views.base)
  (import-vars 'sysrev.views.main)
  (import-vars 'sysrev.shared.keywords)
  (import-vars 'sysrev.shared.spec.core)
  (import-vars 'sysrev.shared.spec.article)
  (import-vars 'sysrev.nav)
  (import-vars 'sysrev.loading)
  (import-vars 'sysrev.pdf)
  nil)

(populate-user-ns)

(sysrev.core/dev-setup)
