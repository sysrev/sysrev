(ns sysrev.shared.spec.web-api
  (:require [clojure.spec :as s]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.util :refer [in?]]))

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
