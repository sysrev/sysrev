(ns sysrev.shared.plans-info
  (:require [clojure.string :as str]))

(def version-suffix "_v3")

(def premium-product "Premium")

(def default-plan "Basic")

(def pro-prefix "Unlimited_Org")

(def unlimited-org (str pro-prefix version-suffix))
(def unlimited-org-annual (str pro-prefix "_Annual" version-suffix))
(def unlimited-user unlimited-org)
(def unlimited-user-annual unlimited-org-annual)

(defn pro? [plan-nickname]
  (and
   (string? plan-nickname)
   (or (= premium-product plan-nickname)
       (str/starts-with? plan-nickname pro-prefix))))
