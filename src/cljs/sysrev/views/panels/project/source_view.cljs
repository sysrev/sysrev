(ns sysrev.views.panels.project.source-view
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.shared.util :refer [parse-integer]]
            [medley.core :refer [deep-merge]]
            [sysrev.views.semantic :refer [Button Tab]]
            [sysrev.views.reagent-json-view :refer [ReactJSONView]]))

(def state (r/atom {}))

(defn retrieve-sample-article!
  [source-id]
  (GET (str "/api/sources/" source-id "/sample-article")
       {:params {:project-id @(subscribe [:active-project-id])}
        :headers {"x-csrf-token" @(subscribe [:csrf-token])}
        :handler (fn [response]
                   (reset! (r/cursor state [source-id :sample-article])
                           (-> response :result :article)))
        :error-handler (fn [_]
                         (.log js/console "[sysrev.views.panels.project.source-view] error in retrieve-sample-article! for " source-id))}))

(defn cursor-val->map
  "Given a cursor-val of the form [(kw_1|integer_1) ... <kw_i|integer_i> val], return a map representation of the cursor-val"
  [cursor-val]
  (cond (= (count cursor-val) 1)
        (first cursor-val)
        (number? (first cursor-val))
        (vector (cursor-val->map (rest cursor-val)))
        :else
        (hash-map (first cursor-val) (cursor-val->map (rest cursor-val)))))

;;[[:foo :bar] [:foo :bar :baz 0 :foo 0 :baz 0]]  {:foo {:bar {:baz [{:foo [{:baz "bar" :foo1 "1"} "baz" "qux"] :bar ["1" "2" "3"]} {:foo ["a" "c"]}]}} :bar "baz"}
;; Throw out the second vector in this case because all of the :bar values are kept
;; [:foo :bar :baz 0] -> take the vector of :baz as the value
;; [:foo :bar :baz 0 :foo] -> take value of :baz, but filter only to the keys of :foo
;; [:foo :bar :baz 0 :foo 0] -> equivalent to above
;; [:foo :bar :baz 0 :foo 0 :baz] -> keeps only the :baz keys in the :foo keys

(defn prune-cursor 
  "Prune a cursor down to its first index.
  e.g. [:foo :bar 0 :baz 1 :quz] -> [:foo :bar]"
  [v & [pruned-cursor]]
  (let [kw (first v)
        pruned-cursor (or pruned-cursor [])]
    (cond (not (seq v))
          pruned-cursor
          (number? kw)
          pruned-cursor
          :else
          (prune-cursor (into []
                              (rest v))
                        (conj pruned-cursor kw)))))
(defn map-from-cursors
  "Given a coll of cursors and edn map, m, extract the values
  the cursors point to and create a new map that is the combination of those cursors"
  [m coll]
  (let [cursor-vals (map #(conj % (get-in m %)) coll)]
    (if (and (seq coll)
             (seq m))
      (->> (map cursor-val->map cursor-vals)
           (apply deep-merge))
      {})))

(defn EditJSONView
  "Edit the JSON view for source. The editing-view? atom is passed as a prop"
  [{:keys [source editing-view?]}]
  (let [source-id (:source-id source)
        sample-article (r/cursor state [source-id :sample-article])
        json (r/cursor state [source-id :sample-article :json])
        ;; change to specific cursor-atom
        cursor-atom (r/cursor state [:cursor-atom])]
    (when-not (seq @sample-article)
      (retrieve-sample-article! source-id))
    (when (nil? cursor-atom)
      (reset! cursor-atom []))
    [:div
     (when (seq @json)
       [Tab {:panes
             [{:menuItem "Edit JSON"
               :render
               (fn []
                 (r/as-component
                  [ReactJSONView
                   {:json (clj->js @json)
                    :on-click (fn [e context]
                                (.preventDefault e)
                                (.stopPropagation e)
                                (let [ns (:ns context)
                                      cursor (->> (clojure.string/split ns #" ")
                                                  (mapv #(if (parse-integer %)
                                                           (parse-integer %)
                                                           (keyword %)))
                                                  prune-cursor)
                                      value (get-in @json cursor)]
                                  (swap! cursor-atom conj cursor)
                                  ;; remove redundant cursors
                                  (reset! cursor-atom (distinct @cursor-atom))
                                  (.log js/console "source-id: " source-id)
                                  (.log js/console "cursor: " (clj->js cursor))))}]))
               ;;:compact true
               :fluid true}
              {:menuItem "Preview Changes"
               :render (fn [] (r/as-component
                               [ReactJSONView {:json (clj->js (map-from-cursors @json @cursor-atom))}]))
               ;;:compact true
               :fluid true}]}])
     [:div
      [Button {:fluid true
               :size "tiny"
               :onClick #(.log js/console "I would have saved")} "Save"]
      [Button {:fluid true
               :size "tiny"
               :style {:margin-top "0.5em"
                       :margin-right "0"}
               :onClick #(swap! editing-view? not)}
       "Cancel"]]]))
