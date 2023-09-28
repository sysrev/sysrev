(ns sysrev.user.interface.spec
  (:require [clojure.spec.alpha :as s]
            [lambdaisland.regal :as regal]
            [sysrev.shared.spec.core :as sc]
            [sysrev.util :refer [in?]]))

(def all-user-settings
  [:ui-theme :dev-account-enabled?])

(def regal-username
  [:cat
   :start
   [:* [:cat [:+ [:class [\A \Z] [\a \z] [\0 \9]]] \-]]
   [:+ [:class [\A \Z] [\a \z] [\0 \9]]]
   :end])

(def re-username (regal/regex regal-username))

(s/def ::user-id ::sc/sql-serial-id)
(s/def ::email string?)
(s/def ::pw-encrypted-buddy (s/nilable string?))
(s/def ::verify-code (s/nilable string?))
(s/def ::verified boolean?)
(s/def ::date-created (s/nilable inst?))
(s/def ::name (s/nilable string?))
(s/def ::username (s/nilable (s/and string?
                                    #(<= 1 (count %) 40)
                                    #(boolean (re-matches re-username %)))))
(s/def ::admin boolean?)
(s/def ::permissions (s/nilable (s/coll-of string?)))
(s/def ::user-uuid ::sc/uuid)
(s/def ::reset-code (s/nilable string?))
(s/def ::setting (s/and keyword? (in? all-user-settings)))
(s/def ::settings (s/nilable (s/map-of ::setting any?)))

(s/def ::user
  (s/keys :req-un [::user-id]
          :opt-un [::email ::pw-encrypted-buddy ::verify-code ::verified
                   ::date-created ::name ::username ::admin ::permissions
                   ::user-uuid ::reset-code ::settings]))

