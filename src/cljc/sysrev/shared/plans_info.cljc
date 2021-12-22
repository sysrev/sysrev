(ns sysrev.shared.plans-info
  (:require [clojure.string :as str]))

(def version-suffix "_v2")

(def premium-product "Premium")

(def default-plan "Basic")

(def pro-prefix "Unlimited_Org")

(def unlimited-org (str pro-prefix version-suffix))
(def unlimited-org-annual (str pro-prefix "_Annual" version-suffix))
(def unlimited-user unlimited-org)
(def unlimited-user-annual unlimited-org-annual)

(def basic-plans    #{default-plan})
(def org-pro-plans  #{unlimited-org unlimited-org-annual})
(def legacy-plans   #{"Unlimited_Org" "Unlimited_Org_Annual"})
(def user-pro-plans (conj org-pro-plans #{unlimited-user unlimited-user-annual}))
(def pro-plans      (set (concat user-pro-plans legacy-plans org-pro-plans)))

(defn pro? [plan-nickname]
  (and
    (string? plan-nickname)
    (str/starts-with? plan-nickname pro-prefix)))

;; Everyone is Premium (formerly team pro) now
(def user-pro? pro?)
(def org-pro? pro?)

(defn basic? [plan-nickname]
  (not (pro? plan-nickname)))
