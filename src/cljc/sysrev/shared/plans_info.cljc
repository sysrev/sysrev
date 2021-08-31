(ns sysrev.shared.plans-info
  (:require [clojure.string :as str]))


(def premium-product "Premium")

(def default-plan "Basic")
(def unlimited-org "Unlimited_Org")
(def unlimited-org-annual "Unlimited_Org_Annual")
(def unlimited-user unlimited-org)
(def unlimited-user-annual unlimited-org-annual)


(def basic-plans    #{default-plan})
(def org-pro-plans  #{unlimited-org unlimited-org-annual})
(def user-pro-plans (conj org-pro-plans #{unlimited-user unlimited-user-annual}))
(def pro-plans      (set (concat user-pro-plans org-pro-plans)))

(def products
  {"Premium"
   {:display "Premium"
    :pro? true}

   "Basic"
   {:display "Basic"
    :pro? false}})

(defn pro? [plan-nickname]
  (if-let [product (products plan-nickname)]
    (:pro? product)
    (contains? pro-plans plan-nickname)))

;; Everyone is Premium (formerly team pro) now
(def user-pro? pro?)
(def org-pro? pro?)

(defn basic? [plan-nickname]
  (if-let [product (products plan-nickname)]
    (not (:pro? product)) 
    (contains? basic-plans plan-nickname)))
