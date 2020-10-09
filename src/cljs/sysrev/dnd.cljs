(ns sysrev.dnd
  (:require [reagent.core :as r]
            ["react-dnd" :refer [DndProvider DragSource DropTarget]]
            ["react-dnd-html5-backend" :refer [HTML5Backend]]
            [sysrev.util :as util :refer [clojurize-map]]))

(def ^:private dnd-provider-react (r/adapt-react-class DndProvider))

(defn wrap-dnd-app
  "Injects react-dnd functionality to a top-level reagent `app` component."
  [app]
  [dnd-provider-react {:backend HTML5Backend} app])

(defn make-drag-spec
  "Creates a js spec object for DragSource (provides hook functions)."
  [{:keys [can-drag is-dragging begin-drag end-drag]}]
  (clj->js
   (cond-> {}
     begin-drag
     (merge {:beginDrag
             (fn [props monitor component]
               (clj->js (begin-drag   (clojurize-map props) monitor component)))})
     can-drag
     (merge {:canDrag
             (fn [props]
               (clj->js (can-drag     (clojurize-map props))))})
     is-dragging
     (merge {:isDragging
             (fn [props monitor]
               (clj->js (is-dragging  (clojurize-map props) monitor)))})
     end-drag
     (merge {:endDrag
             (fn [props monitor component]
               (clj->js (end-drag     (clojurize-map props) monitor component)))}))))

(defn make-drop-spec
  "Creates a js spec object for DropTarget (provides hook functions)."
  [{:keys [drop hover can-drop]}]
  (clj->js
   (cond-> {}
     drop
     (merge {:drop
             (fn [props monitor component]
               (clj->js (drop      (clojurize-map props) monitor component)))})
     hover
     (merge {:hover
             (fn [props monitor component]
               (clj->js (hover     (clojurize-map props) monitor component)))})
     can-drop
     (merge {:canDrop
             (fn [props monitor]
               (clj->js (can-drop  (clojurize-map props) monitor)))}))))

;; TODO: improve `wrap-dnd` to support using only one of DragSource or
;;       DropTarget; allow no value for either `drag-spec` or
;;       `drop-spec`

(defn wrap-dnd
  "Wraps a Hiccup form taken from function `content` to add react-dnd
  functionality as a DragSource and DropTarget.

  Returns a Reagent component function of one argument `id-spec`; this
  will be included in the component props and should include
  information to uniquely identify each item.

  `content` should be a function of one argument `props` and return a
  Hiccup form for the component content. `props` is provided from a
  React component attached to react-dnd.

  `item-type` should be a string indicating the type of item being
  dragged.

  `drag-spec` implements drag source functionality and should be
  created from `make-drag-spec`. `drop-spec` implements drop target
  functionality and should be created from `make-drop-spec`.

  `on-enter` and `on-exit` are hook functions called when an item is
  dragged onto a drop target (or dragged off of that target); they
  each take one `props` argument."
  [_id-spec {:keys [item-type drag-spec drop-spec content on-enter on-exit]}]
  (let [collect-drag (fn [^js connect ^js monitor]
                       (clj->js {:connectDragSource (.dragSource connect)
                                 :isDragging (.isDragging monitor)}))
        collect-drop (fn [^js connect ^js monitor]
                       (clj->js {:connectDropTarget (.dropTarget connect)
                                 :isOver (.isOver monitor)
                                 :isOverCurrent (.isOver monitor (clj->js {:shallow true}))
                                 :canDrop (.canDrop monitor)
                                 :itemType (.getItemType monitor)
                                 :item (.getItem monitor)}))
        component (r/reactify-component
                   (r/create-class
                    {:component-did-update
                     (fn [this old-argv]
                       (when (or on-enter on-exit)
                         (let [old-props (clojurize-map (second old-argv))
                               new-props (-> (clojurize-map (r/props this))
                                             (dissoc :connect-drag-source
                                                     :connect-drop-target))]
                           (when (and on-enter
                                      (:is-over new-props)
                                      (not (:is-over old-props)))
                             (on-enter new-props))
                           (when (and on-exit
                                      (not (:is-over new-props))
                                      (:is-over old-props))
                             (on-exit new-props)))))
                     :render
                     (fn [this]
                       (let [{:keys [connect-drag-source connect-drop-target]
                              :as props} (clojurize-map (r/props this))
                             props (dissoc props :connect-drag-source :connect-drop-target)]
                         (connect-drop-target
                          (connect-drag-source
                           (r/as-element (content props))))))}))
        dnd-component (r/adapt-react-class
                       ((DragSource item-type drag-spec collect-drag)
                        ((DropTarget item-type drop-spec collect-drop)
                         component)))]
    (fn [id-spec]
      [dnd-component {:id-spec (clojurize-map id-spec)}])))
