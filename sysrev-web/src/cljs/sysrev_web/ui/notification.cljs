(ns sysrev-web.ui.notification
  (:require [sysrev-web.base :refer [notify-head notify-pop]]))

(defn notifier [head]
  (fn [head]
    (when-not (empty? head)
      [:div.ui.middle.aligned.large.warning.message
         {:style {:position "fixed"
                     :bottom "0px"
                     :height "100px"
                     :width "auto"
                     :right "0px"}}
       head])))
