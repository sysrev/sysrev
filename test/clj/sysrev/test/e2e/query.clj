(ns sysrev.test.e2e.query)

(defn error-message [message]
  {:fn/has-classes [:message :negative]
   :fn/has-text message})
