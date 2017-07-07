(ns sysrev.shared.spec.web-api
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.util :refer [in?]]
            [clojure.string :as str]))

(s/def ::name keyword?)
(s/def ::arg keyword?)
(s/def ::required (s/coll-of ::arg))
(s/def ::optional (s/coll-of ::arg))
(s/def ::doc (s/nilable string?))
(s/def ::require-token? boolean?)
(s/def ::check-answers? boolean?)
(s/def ::require-admin? boolean?)
(s/def ::project-role keyword?)
(s/def ::handler fn?)
(s/def ::method (s/and keyword? (in? [:get :post])))

(s/def ::pmid integer?)
(s/def ::nct (s/and string? #(str/starts-with? % "NCT")))
(s/def ::arm-name string?)
(s/def ::arm-desc string?)
(s/def ::nct-arm-import
  (s/keys :req-un [::pmid ::nct ::arm-name ::arm-desc]))
(s/def ::nct-arm-imports
  (s/coll-of ::nct-arm-import))
