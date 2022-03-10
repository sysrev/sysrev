(ns sysrev.dnd
  (:require ["react-dnd" :refer [DndProvider]]
            ["react-dnd-html5-backend" :refer [HTML5Backend]]
            [reagent.core :as r]
            [sysrev.util :as util]))

(def ^:private dnd-provider-react (r/adapt-react-class DndProvider))

(defn wrap-dnd-app
  "Injects react-dnd functionality to a top-level reagent `app` component."
  [app]
  [dnd-provider-react {:backend HTML5Backend} app])
