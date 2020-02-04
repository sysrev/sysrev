(ns sysrev.views.reagent-json-view
  (:require [sysrev.views.html :refer [html]]
            [sysrev.views.semantic :refer [Icon]]
            [clojure.string :as st]
            [reagent.core :as r]))

(defn ns-str
  "Create a new namespace given a root and leaf"
  [root & [leaf]]
  (let [namespace (clojure.string/trim (str root " " leaf))]
    (if (seq namespace)
      namespace
      "root")))

;; this is from https://github.com/yogthos/json-html
;; MIT License
(defn render-keyword [k]
  (str (->> k ((juxt namespace name)) (remove nil?) (st/join "/"))))

(def url-regex ;; good enough...
  (re-pattern "(\\b(https?|ftp|file|ldap)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|])"))

(defn linkify-links
  "Make links clickable."
  [string]
  (clojure.string/replace string url-regex "<a class='jh-type-string-link' href=$1>$1</a>"))

(defprotocol Render
  (render [this] "Renders the element a Hiccup structure"))

(defn escape-html [s]
  (st/escape s
             {"&" "&amp;"
              ">" "&gt;"
              "<" "&lt;"
              "\"" "&quot;"}))

(defn- obj->clj [obj]
  (reduce
   (fn [props k]
     (assoc props (keyword k) (aget obj k)))
   {}
   (js/Object.keys obj)))

(declare render-html)

(defn IndexedVal [{:keys [namespace on-add i v]}]
  (let [displayed? (r/atom true)]
    (fn []
      [:div [:span.jh-key.jh-array-key
             {:data-namespace (ns-str namespace i)}
             [Icon {:name (if @displayed?
                            "caret down"
                            "caret right")
                    :on-click (fn [e]
                                (swap! displayed? not))}] i " : "]
       (when @displayed?
         [:span.jh-value.jh-array-value
          {:data-namespace (ns-str namespace i)}
          (render-html v (ns-str namespace i) on-add)])
       (when-not @displayed?
         "{ ... }")])))

(defn render-collection [col namespace & [on-add]]
  (if (empty? col)
    [:div.jh-type-object "{" [:span.jh-empty-collection]]
    [:div.jh-type-object
     [:div.jh-type-array
      "["
      (for [[i v] (map-indexed vector col)]
        ^{:key i} [IndexedVal {:namespace namespace
                               :on-add on-add
                               :i i
                               :v v}])
      "]"]]))

(defn str-compare [k1 k2]
  (compare (str k1) (str k2)))

(defn sort-set [s]
  (try
    (into (sorted-set) s)
    (catch js/Error _
      (into (sorted-set-by str-compare) s))))

;; ns not handled properly yet
(defn render-set [s namespace & [on-add]]
  (if (empty? s)
    [:div.jh-type-set [:span.jh-empty-set]]
    [:ul (for [item (sort-set s)] [:li.jh-value (render-html item namespace on-add)])]))

(defn sort-map [m]
  (let [m (mapv (fn [[k v]]
                  [(if (= (type k) js/Object) (js->clj k) k) v])
                m)]
    (try
      (into (sorted-map) m)
      (catch js/Error _
        (into (sorted-map-by str-compare) m)))))

(defn MapVal [{:keys [namespace on-add k v]}]
  (let [displayed? (r/atom true)]
    [:div.jh-obj-div
     [Icon {:name (if @displayed?
                    "caret down"
                    "caret right")
            :on-click (fn [_]
                        (swap! displayed? not))}]
     [:span.jh-key.jh-object-key {:data-namespace (ns-str namespace)} (render-html k (ns-str namespace (-> k symbol str)) on-add) ": "]
     (when @displayed?
       [:div.jh-value.jh-object-value
        {:data-namespace (ns-str namespace (-> k symbol str))}
        (render-html v (ns-str namespace (-> k symbol str)) on-add) ])
     (when-not @displayed?
       "{ ... }")]))

(defn render-map [m namespace & [on-add]]
  (let [displayed? (r/atom true)]
    (if (empty? m)
      [:div.jh-type-object "{" [:span.jh-empty-map  "}"]]
      [:div.jh-type-object "{"
       [:div.jh-type-val
        (for [[k v] (sort-map m)]
          ^{:key k}
          [MapVal {:namespace namespace
                   :on-add on-add
                   :k k
                   :v v}])] "}"])))

(defn StringVal [s namespace & [on-add]]
  (let [hover? (r/atom false)]
    (r/create-class
     {:reagent-render
      (fn [s namespace & [on-add]]
        [:span.jh-type-string {:data-namespace (ns-str namespace)
                               :on-mouse-over (fn [e]
                                                (.stopPropagation e)
                                                (reset! hover? true))
                               :on-mouse-leave (fn [e]
                                                 (.stopPropagation e)
                                                 (reset! hover? false))}
         (if (st/blank? s)
           [:span.jh-empty-string {:on-click #(on-add % {:ns (ns-str namespace)})}]
           (escape-html s))
         (when @hover?
           [:span
            (when-not (nil? on-add)
              [Icon {:name "add square"
                     :on-click #(on-add % {:ns (ns-str namespace)})}])])])})))

(defn render-html [v namespace & [on-add]]
  (let [namespace (or namespace "")
        t (type v)]
    (cond
      (satisfies? Render v) (render v)
      (= t Keyword) [:span.jh-type-kw (render-keyword v)]
      (= t Symbol) [:span.jh-type-symbol (str v)]
      (= t js/String) #_[:span.jh-type-string (render-string v)]
      [StringVal v namespace on-add]
      (= t js/Date) [:span.jh-type-date (.toString v)]
      (= t js/Boolean) [:span.jh-type-bool (str v)]
      (= t js/Number) [:span.jh-type-number v]
      (= t js/Array) (render-html (js->clj v) namespace on-add)
      (satisfies? IMap v) (render-map v namespace on-add)
      (satisfies? ISet v) (render-set v on-add)
      (satisfies? ICollection v) (render-collection v namespace on-add)
      (= t js/Object) (render-html (obj->clj v) namespace on-add)
      nil [:span.jh-empty nil])))

(defn edn->hiccup [edn]
  [:div.jh-root (render-html edn "")])

(defn edn->html [edn]
  (linkify-links (html (edn->hiccup edn))))

(defn json->hiccup [{:keys [json on-add]}]
  [:div.jh-root
   (render-html (js->clj json :keywordize-keys true) "" on-add)])

#_(defn json->html [json & [on-add]]
    (linkify-links (html (json->hiccup json "" on-add))))

(defn ReactJSONView
  "JSON is the a json object. on-add is a function of context and event, corresponds to when user clicks the + icon."
  [{:keys [json on-add]}]
  (json->hiccup {:json json :on-add on-add}))
