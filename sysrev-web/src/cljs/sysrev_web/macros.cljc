(ns sysrev-web.macros)

(defmacro with-state [state-map & body]
  `(binding [sysrev-web.base/state (reagent.core/atom ~state-map)]
     ~@body))
