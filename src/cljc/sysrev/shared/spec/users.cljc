(ns sysrev.shared.spec.users
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.util :refer [in?]]))

(def all-user-settings
  [:ui-theme])

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
(s/def ::reset-code (s/nilable string?))
(s/def ::setting (s/and keyword? (in? all-user-settings)))
(s/def ::settings (s/nilable (s/map-of ::setting any?)))
