(ns sysrev.shared.spec.notes
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

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
          [::article-note-id ::article-id ::user-id ::content ::added-time ::updated-time]))

(s/def ::member-notes-map
  (s/map-of
   ::sc/article-id
   (s/map-of ::name ::content)))
