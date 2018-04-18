(ns sysrev.spec.db
  (:require [clojure.spec.alpha :as s]
            [sysrev.spec.core :as csc]
            [sysrev.spec.identity :as csi]))

(s/def ::csrf-token string?)

(s/def ::active-panel keyword?)
(s/def ::needed (s/coll-of ::csc/path))

(s/def ::state
  (s/keys
   :opt-un [::csi/identity
            ::active-panel]))
(s/def ::data
  (s/keys
   :opt-un []))
(s/def ::db
  (s/keys
   :opt-un [::csrf-token
            ::state
            ::data
            ::needed]))
