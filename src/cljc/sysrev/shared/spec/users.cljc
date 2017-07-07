(ns sysrev.shared.spec.users
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

(s/def ::user-id ::sc/sql-serial-id)
(s/def ::email string?)
(s/def ::pw-encrypted-buddy (s/nilable string?))
(s/def ::verify-code (s/nilable string?))
(s/def ::verified boolean?)
(s/def ::date-created (s/nilable inst?))
(s/def ::name (s/nilable string?))
(s/def ::username (s/nilable string?))
(s/def ::admin boolean?)
(s/def ::permissions (s/nilable (s/coll-of string?)))
(s/def ::user-uuid ::sc/uuid)
(s/def ::default-project-id (s/nilable ::sc/project-id))
(s/def ::reset-code (s/nilable string?))

(s/def ::web-user
  (s/keys :req-un
          [::user-id ::email ::pw-encrypted-buddy ::verify-code
           ::verified ::date-created ::name ::username ::admin
           ::permissions ::user-uuid ::default-project-id ::reset-code]))
(s/def ::web-user-partial
  (s/keys :opt-un
          [::user-id ::email ::pw-encrypted-buddy ::verify-code
           ::verified ::date-created ::name ::username ::admin
           ::permissions ::user-uuid ::default-project-id ::reset-code]))
