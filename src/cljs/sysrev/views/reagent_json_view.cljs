(ns sysrev.views.reagent-json-view
  (:require [clojure.string :as str]
            [sysrev.views.html :refer [html]]
            [sysrev.views.semantic :refer [Icon]]
            [reagent.core :as r]))

(defn ns-str
  "Create a new namespace given a root and leaf"
  [root & [leaf]]
  (or (not-empty (str/trim (str root " " leaf)))
      "root"))

;; this is from https://github.com/yogthos/json-html
;; MIT License
(defn render-keyword [k]
  (str (->> k ((juxt namespace name)) (remove nil?) (str/join "/"))))

(def url-regex ;; good enough...
  (re-pattern "(\\b(https?|ftp|file|ldap)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|])"))

(defn linkify-links
  "Make links clickable."
  [string]
  (str/replace string url-regex "<a class='jh-type-string-link' href=$1>$1</a>"))

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

(defn IndexedVal [i v context]
  (let [{:keys [namespace collapsed]} context
        ns (ns-str namespace i)
        depth (-> ns
                  (clojure.string/split #" ")
                  count)
        displayed? (r/atom (if collapsed
                             (if (<= depth collapsed)
                               true
                               false)
                             true))]
    (r/create-class
     {:reagent-render
      (fn [i v context]
        (let [{:keys [namespace]} context]
          [:div [:span.jh-key.jh-array-key
                 {:data-namespace ns}
                 [Icon {:name (if @displayed?
                                "caret down"
                                "caret right")
                        :on-click (fn [_]
                                    (swap! displayed? not))}] i " : "]
           (when @displayed?
             [:span.jh-value.jh-array-value
              {:data-namespace (ns-str namespace i)}
              (render-html v (assoc context :namespace (ns-str namespace i)))])
           (when-not @displayed?
             "{ ... }")]))})))

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
  (try
    (into (sorted-set) s)
    (catch js/Error _
      (into (sorted-set-by str-compare) s))))

;; ns not handled properly yet
(defn render-set [s context]
  (if (empty? s)
    [:div.jh-type-set [:span.jh-empty-set]]
    [:ul (for [item (sort-set s)] [:li.jh-value (render-html item context)])]))

(defn MapVal [k v context]
  (let [{:keys [namespace collapsed]} context
        ns (ns-str namespace (-> k symbol str))
        depth (-> ns
                  (clojure.string/split #" ")
                  count)
        displayed? (r/atom (if collapsed
                             (if (<= depth collapsed)
                               true
                               false)
                             true))]
    (r/create-class
     {:reagent-render
      (fn [k v context]
        (let [{:keys [namespace]} context]
          [:div.jh-obj-div
           [Icon {:name (if @displayed?
                          "caret down"
                          "caret right")
                  :on-click (fn [_]
                              (swap! displayed? not))}]
           [:span.jh-key.jh-object-key {:data-namespace ns}
            (render-html k
                         (assoc context :namespace ns)) ": "]
           (when @displayed?
             [:div.jh-value.jh-object-value
              {:data-namespace ns}
              (render-html v (assoc context :namespace (ns-str namespace (-> k symbol str))))])
           (when-not @displayed?
             "{ ... }")]))})))

(defn render-map [m context]
  (if (empty? m)
    [:div.jh-type-object "{" [:span.jh-empty-map  "}"]]
    [:div.jh-type-object "{"
     [:div.jh-type-val
      (for [[k v] (mapv (fn [[k v]]
                          [(if (= (type k) js/Object) (js->clj k) k) v])
                        m)]
        ^{:key k}
        [MapVal k v context])] "}"]))

(defn StringVal [s context]
  (let [hover? (r/atom false)
        string-class (r/atom "jh-type-string")]
    (r/create-class
     {:reagent-render
      (fn [s context]
        (let [{:keys [on-add on-minus namespace]} context]
          [:span {:class @string-class
                  :data-namespace (ns-str namespace)
                  :on-mouse-over (fn [e]
                                   (.stopPropagation e)
                                   (reset! hover? true))
                  :on-mouse-leave (fn [e]
                                    (.stopPropagation e)
                                    (reset! hover? false))}
           (if (str/blank? s)
             [:span.jh-empty-string (when-not (nil? on-add)
                                      {:on-click #(on-add % (-> context
                                                                (dissoc :on-click)
                                                                (assoc :string-class-atom string-class)))})]
             (escape-html s))
           (when @hover?
             [:span
              (when-not (nil? on-add)
                [Icon {:name "add square"
                       :on-click #(on-add % (-> context
                                                (dissoc :on-click)
                                                (assoc :string-class-atom string-class)))}])
              (when-not (nil? on-minus)
                [Icon {:name "minus square"
                       :on-click #(on-minus % (-> context
                                                  (dissoc :on-click)
                                                  (assoc :string-class-atom string-class)))}])])]))})))

(defn- render-html [v context]
  (let [t (type v)]
    (cond
      (satisfies? Render v) (render v)
      (= t Keyword) [:span.jh-type-kw (render-keyword v)]
      (= t Symbol) [:span.jh-type-symbol (str v)]
      (= t js/String) [StringVal v context]
      (= t js/Date) [:span.jh-type-date (.toString v)]
      (= t js/Boolean) [:span.jh-type-bool (str v)]
      (= t js/Number) [:span.jh-type-number v]
      (= t js/Array) (render-html (js->clj v) context)
      (satisfies? IMap v) (render-map v context)
      (satisfies? ISet v) (render-set v context)
      (satisfies? ICollection v) (render-collection v context)
      (= t js/Object) (render-html (obj->clj v) context)
      nil [:span.jh-empty nil])))

(defn edn->hiccup [edn]
  [:div.jh-root (render-html edn "")])

(defn edn->html [edn]
  (linkify-links (html (edn->hiccup edn))))

(defn json->hiccup [json & [context]]
  [:div.jh-root
   (render-html (js->clj json :keywordize-keys true) (or context {}))])

(defn ReactJSONView
  "JSON is the a json object. context is a map, it can contain on-add and on-minus."
  [json & [context]]
  (json->hiccup json context))
