(ns sysrev-web.ui.notification)

(defn notifier [head pop-handle timeout]
  (fn [head]
    (when-not (empty? head)
      ;; On re-render, this will call the provided
      ;; handler to pop the head out of the queue.
      (js/setTimeout pop-handle timeout)
      [:div.ui.middle.aligned.large.warning.message
         {:style {:position "fixed"
                     :bottom "0px"
                     :height "100px"
                     :width "auto"
                     :right "0px"}}
       head])))

