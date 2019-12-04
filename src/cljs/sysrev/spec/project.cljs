(ns sysrev.spec.project
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

(s/def ::permissions (s/coll-of string?))
(s/def ::article-ids (s/coll-of ::sc/article-id))
(s/def ::includes ::article-ids)
(s/def ::excludes ::article-ids)
(s/def ::in-progress (and integer? nat-int?))
(s/def ::articles
  (s/nilable
   (s/keys :req-un [::includes ::excludes])))
(s/def ::members
  (s/map-of ::sc/user-id ::member))
(s/def ::member
  (s/keys :req-un [::permissions ::articles ::in-progress]))
(s/def ::project
  (s/keys
   :req-un [::sc/project-id ::members
            ;; more fields here
            ]))
