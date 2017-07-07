(ns sysrev.shared.spec.notes
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

;;;
;;; `project-note` table
;;;

(s/def ::project-note-id ::sc/uuid)
(s/def ::project-id ::sc/project-id)
(s/def ::name string?)
(s/def ::description (s/nilable string?))
(s/def ::max-length (s/and integer? nat-int?))
(s/def ::ordering (s/nilable integer?))

(s/def ::project-note
  (s/keys :req-un
          [::project-note-id ::project-id ::name ::description
           ::max-length ::ordering]))
(s/def ::project-note-partial
  (s/keys :opt-un
          [::project-note-id ::project-id ::name ::description
           ::max-length ::ordering]))

(s/def ::project-notes-map
  (s/map-of ::name ::project-note))

;;;
;;; `article-note` table
;;;

(s/def ::article-note-id ::sc/uuid)
(s/def ::article-id ::sc/article-id)
(s/def ::user-id ::sc/user-id)
(s/def ::content (s/nilable string?))
(s/def ::added-time inst?)
(s/def ::updated-time inst?)

(s/def ::article-note
  (s/keys :req-un
          [::article-note-id ::article-id ::user-id ::content
           ::added-time ::updated-time]))
(s/def ::article-note-partial
  (s/keys :opt-un
          [::article-note-id ::article-id ::user-id ::content
           ::added-time ::updated-time]))

(s/def ::member-notes-map
  (s/map-of
   ::sc/article-id
   (s/map-of ::name ::content)))
