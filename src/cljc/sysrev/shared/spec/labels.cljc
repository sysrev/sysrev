(ns sysrev.shared.spec.labels
  (:require [clojure.spec :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.project :as sp]))

(s/def ::label-id ::sc/uuid)
(s/def ::label-id-local ::sc/sql-serial-id)
(s/def ::project-id ::sp/project-id)
(s/def ::project-ordering (s/nilable integer?))
(s/def ::value-type string?)
(s/def ::name string?)
(s/def ::question string?)
(s/def ::short-label (s/nilable string?))
(s/def ::required boolean?)
(s/def ::category string?)
(s/def ::definition (s/nilable map?))
(s/def ::enabled boolean?)

(s/def ::label
  (s/keys :req-un
          [::label-id ::label-id-local ::project-id ::project-ordering
           ::value-type ::name ::question ::short-label ::required
           ::category ::definition ::enabled]))
(s/def ::label-partial
  (s/keys :opt-un
          [::label-id ::label-id-local ::project-id ::project-ordering
           ::value-type ::name ::question ::short-label ::required
           ::category ::definition ::enabled]))

;;
(s/def ::answer any?)
(s/def ::confirm-time inst?)
(s/def ::confirmed boolean?)

(s/def ::member-article-answers
  (s/coll-of
   (s/keys :req-un
           [::sc/label-id ::answer])))

(s/def ::member-answers
  (s/map-of ::sc/article-id ::member-article-answers))
