(ns sysrev.spec.identity
  (:require [clojure.spec :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.users :as su]))

(s/def ::projects (s/coll-of ::sc/project-id))

(s/def ::identity
  (s/nilable
   (s/keys :req-un [::sc/user-id ::su/user-uuid ::su/email
                    ::su/verified ::su/permissions ::projects])))
