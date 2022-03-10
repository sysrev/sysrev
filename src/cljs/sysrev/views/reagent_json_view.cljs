(ns sysrev.views.reagent-json-view
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [sysrev.util :as util :refer [wrap-stop-propagation]]
            [sysrev.views.semantic :refer [Icon]]))

(defn ns-str
  "Create a new namespace given a root and leaf"
  [root & [leaf]]
  (or (not-empty (str/trim (str root " " leaf)))
      "root"))

;; this is from https://github.com/yogthos/json-html
;; MIT License
(defn render-keyword [k]
  (str (->> k ((juxt namespace name)) (remove nil?) (str/join "/"))))

(defprotocol Render
  (render [this] "Renders the element a Hiccup structure"))

(defn escape-html [s]
  (str/escape s {"&" "&amp;"
                 ">" "&gt;"
                 "<" "&lt;"
                 "\"" "&quot;"}))

(defn- obj->clj [obj]
  (->> (for [k (js/Object.keys obj)]
         {(keyword k) (aget obj k)})
       (apply merge {})))

(declare render-html)

(defn IndexedVal [i _v context]
  (let [{:keys [namespace collapsed]} context
        ns (ns-str namespace i)
        depth (count (str/split ns #" "))
        displayed? (r/atom (or (not collapsed)
                               (<= depth collapsed)))]
    (fn [i v context]
      (let [context (update context :namespace #(ns-str % i))
            ns (:namespace context)]
        [:div [:span.jh-key.jh-array-key {:data-namespace ns}
               [Icon {:name (if @displayed? "caret down" "caret right")
                      :on-click #(swap! displayed? not)}]
               i " : "]
         (if @displayed?
           [:span.jh-value.jh-array-value {:data-namespace ns}
            (render-html v context)]
           "{ ... }")]))))

(defn render-collection [col context]
  (if (empty? col)
    [:div.jh-type-object "{" [:span.jh-empty-collection]]
    [:div.jh-type-object
     [:div.jh-type-array
      "["
      (for [[i v] (map-indexed vector col)]
        ^{:key i} [IndexedVal i v context])
      "]"]]))

(defn str-compare [k1 k2]
  (compare (str k1) (str k2)))

(defn sort-set [s]
  (try (into (sorted-set) s)
       (catch js/Error _
         (into (sorted-set-by str-compare) s))))

;; ns not handled properly yet
(defn render-set [s context]
  (if (empty? s)
    [:div.jh-type-set [:span.jh-empty-set]]
    [:ul (for [item (sort-set s)]
           [:li.jh-value (render-html item context)])]))

(defn MapVal [k _v context]
  (let [{:keys [namespace collapsed]} context
        ns (ns-str namespace (-> k symbol name))
        depth (count (str/split ns #" "))
        displayed? (r/atom (or (not collapsed)
                               (<= depth collapsed)))]
    (fn [k v context]
      (let [context (update context :namespace #(ns-str % (-> k symbol name)))
            ns (:namespace context)]
        [:div.jh-obj-div
         [Icon {:name (if @displayed? "caret down" "caret right")
                :on-click #(swap! displayed? not)}]
         [:span.jh-key.jh-object-key {:data-namespace ns}
          (render-html k context) ": "]
         (if @displayed?
           [:div.jh-value.jh-object-value {:data-namespace ns}
            (render-html v context)]
           "{ ... }")]))))

(defn render-map [m context]
  (if (empty? m)
    [:div.jh-type-object "{" [:span.jh-empty-map "}"]]
    [:div.jh-type-object "{"
     [:div.jh-type-val
      (for [[k v] m]
        (let [k (if (= (type k) js/Object)
                  (js->clj k) k)]
          ^{:key k} [MapVal k v context]))] "}"]))

(defn StringVal [_s _context]
  (let [hover? (r/atom false)
        string-class (r/atom "jh-type-string")]
    (fn [s context]
      (let [{:keys [on-add on-minus namespace]} context
            click-context (-> (assoc context :string-class-atom string-class)
                              (dissoc :on-click))]
        [:span {:class           @string-class
                :data-namespace  (ns-str namespace)
                :on-mouse-over   (wrap-stop-propagation #(reset! hover? true))
                :on-mouse-leave  (wrap-stop-propagation #(reset! hover? false))}
         (if (str/blank? s)
           [:span.jh-empty-string {:on-click (when on-add #(on-add % click-context))}]
           (escape-html s))
         (when @hover?
           [:span
            (when on-add
              [Icon {:name "add square" :on-click #(on-add % click-context)}])
            (when on-minus
              [Icon {:name "minus square" :on-click #(on-minus % click-context)}])])]))))

(defn- render-html [v context]
  (let [t (type v)]
    (cond (satisfies? Render v)      (render v)
          (= t Keyword)              [:span.jh-type-kw (render-keyword v)]
          (= t Symbol)               [:span.jh-type-symbol (str v)]
          (= t js/String)            [StringVal v context]
          (= t js/Date)              [:span.jh-type-date (.toString v)]
          (= t js/Boolean)           [:span.jh-type-bool (str v)]
          (= t js/Number)            [:span.jh-type-number v]
          (= t js/Array)             (render-html (js->clj v) context)
          (satisfies? IMap v)        (render-map v context)
          (satisfies? ISet v)        (render-set v context)
          (satisfies? ICollection v) (render-collection v context)
          (= t js/Object)            (render-html (obj->clj v) context)
          (= t nil)                  [:span.jh-empty nil])))

(defn json->hiccup [json & [context]]
  [:div.jh-root
   (render-html (js->clj json :keywordize-keys true) (or context {}))])

(defn ReactJSONView
  "JSON is the a json object. context is a map, it can contain on-add and on-minus."
  [json & [context]]
  (json->hiccup json context))
