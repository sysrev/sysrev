(ns sysrev.shared.spec.core
  (:require [clojure.spec.alpha :as s]))

(s/def ::uuid uuid?)
(s/def ::sql-serial-id (s/and int? nat-int?))
;; this is used to allow either a uuid or integer id where appropriate
(s/def ::sql-id (s/or :uuid ::uuid
                      :serial ::sql-serial-id))

(s/def ::article-id ::sql-id)
(s/def ::project-id ::sql-id)
(s/def ::user-id ::sql-id)
(s/def ::label-id ::sql-id)
(s/def ::group-id ::sql-id)
(s/def ::invitation-id ::sql-id)
