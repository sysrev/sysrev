(ns sysrev.views.base
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [clojure.string :as str]))

(defn- active-panel []
  @(subscribe [:active-panel]))

(defmulti panel-content #(or % (active-panel)))
(defmulti logged-out-content #(or % (active-panel)))

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
