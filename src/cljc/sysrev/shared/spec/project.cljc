(ns sysrev.shared.spec.project
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

(s/def ::project-id ::sc/sql-serial-id)
(s/def ::name string?)
(s/def ::enabled boolean?)
(s/def ::project-uuid ::sc/uuid)
(s/def ::date-created inst?)

;; project settings
(s/def ::second-review-prob (s/nilable number?))
(s/def ::public-access (s/nilable boolean?))
(s/def ::settings
  (s/keys :opt-un [::second-review-prob ::public-access]))

;; map with all columns of `project` table required
(s/def ::project
  (s/keys :req-un
          [::project-id ::name ::enabled ::project-uuid ::date-created
           ::settings]))
;; project map with fields optional
(s/def ::project-partial
  (s/keys :opt-un
          [::project-id ::name ::enabled ::project-uuid ::date-created
           ::settings]))

;;
;; `project-member` table
;;

(s/def ::user-id ::sc/user-id)
(s/def ::join-date inst?)
(s/def ::permissions (s/nilable (s/coll-of string?)))
(s/def ::membership-id (s/and integer? nat-int?))

(s/def ::project-member
  (s/keys :req-un
          [::project-id ::user-id ::join-date ::permissions
           ::enabled ::membership-id]))
