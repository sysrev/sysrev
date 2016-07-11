;; This can be used to run figwheel from an existing Clojure REPL.
;; It is not used in any way by the template config.

(use 'figwheel-sidecar.repl-api)
(start-figwheel!) ;; <-- fetches configuration
(cljs-repl)
