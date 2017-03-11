(ns sysrev.macros
  (:require [cljs.analyzer.api :as ana-api]))

(defmacro with-state [state-map & body]
  `(binding [sysrev.base/work-state (reagent.core/atom ~state-map)]
     ~@body))

(defmacro using-work-state [& body]
  `(binding [sysrev.base/read-from-work-state true]
     ~@body))

(defmacro with-mount-hook [on-mount]
  `(fn [content#]
     (reagent.core/create-class
      {:component-did-mount
       ~on-mount
       :reagent-render
       (fn [content#] content#)})))

(defmacro import-vars [[_quote ns]]
  `(do
     ~@(->>
        (ana-api/ns-interns ns)
        (remove (comp :macro second))
        (map (fn [[k# _]]
               `(def ~(symbol k#) ~(symbol (name ns) (name k#))))))))
