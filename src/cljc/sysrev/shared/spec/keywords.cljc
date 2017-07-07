(ns sysrev.shared.spec.keywords
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

(s/def ::keyword-id ::sc/uuid)
(s/def ::project-id ::sc/project-id)
(s/def ::user-id (s/nilable ::sc/user-id))
(s/def ::label-id (s/nilable ::sc/label-id))
(s/def ::label-value (s/nilable any?))
(s/def ::value string?)
(s/def ::category string?)
(s/def ::color (s/nilable string?))

(s/def ::project-keyword
  (s/keys :req-un
          [::keyword-id ::project-id ::user-id ::label-id ::label-value
           ::value ::category ::color]))
(s/def ::project-keyword-partial
  (s/keys :opt-un
          [::keyword-id ::project-id ::user-id ::label-id ::label-value
           ::value ::category ::color]))

(s/def ::toks (s/coll-of string?))

(s/def ::keyword-full
  (s/merge ::project-keyword
           (s/keys :req-un [::toks])))

(s/def ::project-keywords-full
  (s/map-of ::keyword-id ::keyword-full))
