(ns sysrev.shared.spec.article
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

(s/def ::article-id ::sc/sql-serial-id)
(s/def ::article-uuid ::sc/uuid)

(s/def ::primary-title string?)
(s/def ::secondary-title (s/nilable string?))
(s/def ::abstract (s/nilable string?))

;; NOTE: work-type field may help to identity conference abstracts
;;       from endnote xml exports
(s/def ::work-type (s/nilable string?))
;;
(s/def ::remote-database-name (s/nilable string?))
(s/def ::year (s/nilable integer?))
(s/def ::authors (s/nilable (s/coll-of string?)))
(s/def ::urls (s/nilable (s/coll-of string?)))
(s/def ::document-ids (s/nilable (s/coll-of string?)))
(s/def ::project-id ::sc/sql-serial-id)
(s/def ::raw (s/nilable string?))
(s/def ::enabled (s/nilable boolean?))
(s/def ::duplicate-of (s/nilable integer?))

;; additional article map fields (not fields of `article` table)
(s/def ::score (s/and (s/nilable number?)
                      #(or (nil? %)
                           (and (<= 0.0 %)
                                (<= % 1.0)))))

(s/def ::external-id string?)
(s/def ::source string?)
(s/def ::location
  (s/keys :req-un [::source ::external-id]))
(s/def ::locations
  (s/nilable (s/map-of ::source (s/coll-of ::location))))
(s/def ::flags
  (s/nilable (s/map-of string? map?)))

;; article map with fields optional
(s/def ::article-partial
  (s/keys :opt-un
          [::article-id ::primary-title ::secondary-title
           ::abstract ::work-type ::remote-database-name
           ::year ::authors ::urls ::document-ids ::project-id ::raw
           ::article-uuid ::enabled ::duplicate-of
           ::score ::locations ::flags]))

(s/def ::article-or-id (s/or :id ::sc/article-id
                             :map ::article))

(s/def ::flag-name string?)
(s/def ::disable boolean?)
(s/def ::meta map?)
