(ns sysrev.views.base
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe dispatch]]))

(defmulti panel-content #(or % @(subscribe [:active-panel])))
(defmulti logged-out-content #(or % @(subscribe [:active-panel])))

(defn render-panel-tree [panel]
  (let [subpanels (map (fn [level]
                         [panel-content (take (inc level) panel)])
                       (range 0 (count panel)))]
    (letfn [(ptree [level]
              (let [subpanel (take level panel)]
                [:div.panel
                 {:id (when (not-empty subpanel)
                        (str/join "_" (map name subpanel)))}
                 ((panel-content subpanel)
                  (when (< level (count panel))
                    (ptree (inc level))))]))]
      (ptree 1))))
