(ns sysrev.macros
  (:require [cljs.analyzer.api :as ana-api]
            [re-frame.core :refer [subscribe dispatch]]))

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

(defmacro with-loader
  "Wraps a UI component to define required data and delay rendering until
  data has been loaded."
  [reqs options content-form]
  `(let [reqs# ~reqs
         options# ~options
         loading# (some #(deref (subscribe [:loading? %])) reqs#)
         have-data# (every? #(deref (subscribe [:have? %])) reqs#)
         content# (fn [] [:div {:style (if (and (:dimmer options#) loading#)
                                         {:visibility "hidden"}
                                         {})}
                          ~content-form])]
     (doseq [item# reqs#]
       (dispatch [:require item#]))
     [:div
      (when (:dimmer options#)
        [:div.ui.inverted.dimmer
         {:class (if loading# "active" "")}
         [:div.ui.loader]])
      (if have-data# [content#] [:div])]))
