(ns sysrev-web.macros)

(defmacro with-state [state-map & body]
  `(binding [sysrev-web.base/state (reagent.core/atom ~state-map)]
     ~@body))

(defmacro with-mount-hook [on-mount content]
  `(r/create-class
    {:component-did-mount
     ~on-mount
     :reagent-render
     (fn [] ~content)}))
