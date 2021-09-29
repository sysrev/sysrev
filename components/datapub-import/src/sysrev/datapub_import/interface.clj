(ns sysrev.datapub-import.interface
  (:require [sysrev.datapub-import.fda-drugs :as fda-drugs]))

(defn import-fda-drugs-docs!
  "Import the ApplicationDocs from the FDA@Drugs dataset."
  [{:keys [auth-token dataset-id endpoint] :as opts}]
  (fda-drugs/import-fda-drugs-docs! opts))
