(ns sysrev.spec.core
  (:require [clojure.spec.alpha :as s]
            [sysrev.shared.spec.core :as sc]))

(s/def ::path vector?)
