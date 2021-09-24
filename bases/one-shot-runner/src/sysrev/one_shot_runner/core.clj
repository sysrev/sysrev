(ns sysrev.one-shot-runner.core
  (:refer-clojure :exclude [run!])
  (:require [sysrev.config.interface :as config]))

(def get-config (partial config/get-config "one-shot-runner-config.edn"))

(defn run! [{:keys [common-opts tasks]}]
  (doseq [[fn-sym opts] tasks]
    ((requiring-resolve fn-sym) (merge common-opts opts))))

(defn -main []
  (run! (get-config)))
