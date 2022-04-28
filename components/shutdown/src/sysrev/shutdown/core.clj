(ns sysrev.shutdown.core
  (:require [clojure.tools.logging :as log]))


(def state (atom {:hook-called? false, :hooks {}}))

(defn add-hook! [thunk]
  (let [uuid (random-uuid)
        dly (delay
             (try
               (thunk)
               (catch Exception e
                 (swap! state update :hooks dissoc uuid)
                 (throw e))))]
    (swap! state
           (fn [{:keys [hook-called?] :as state}]
             (if hook-called?
               (throw (ex-info "Cannot add shutdown hook while shutting down"
                               {:hook thunk}))
               (update state :hooks assoc uuid dly))))
    dly))

(defn run-hooks! []
  (swap! state assoc :hook-called? true)
  (doseq [hook (vals (:hooks @state))]
    (try
      @hook
      (catch Exception e
        (log/error "Exception during shutdown hook" e)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defonce add-global-hook!
  (->> #(run-hooks!)  ;; Wrap in #() to allow redefining by the REPL
       Thread.
       (.addShutdownHook (Runtime/getRuntime))))
