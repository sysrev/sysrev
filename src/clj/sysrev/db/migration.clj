(ns sysrev.db.migration
  (:require [clojure.tools.logging :as log]
            [sysrev.annotations
             :refer [delete-invalid-annotations
                     migrate-old-annotations]]
            [sysrev.db.core :as db]
            [sysrev.group.core :as group]
            [sysrev.label.migrate :refer [migrate-all-project-article-resolve]]
            [sysrev.payment.stripe :as stripe :refer [update-stripe-plans-table]]
            [sysrev.util :as util]))

(defn ensure-groups
  "Ensure that there are always the required SysRev groups"
  []
  (db/with-transaction
    (when-not (group/group-name->id "public-reviewer")
      (group/create-group! "public-reviewer"))))

(defn ensure-updated-db
  "Runs everything to update database entries to latest format."
  []
  (doseq [migrate-fn [#'update-stripe-plans-table
                      #'ensure-groups
                      #'migrate-all-project-article-resolve
                      #'delete-invalid-annotations
                      #'migrate-old-annotations]]
    (log/info "Running " (str migrate-fn))
    (time (try ((var-get migrate-fn))
               (catch Throwable e
                 (log/error "Exception applying migration %s" (pr-str migrate-fn))
                 (util/log-exception e)
                 (throw e))))))
