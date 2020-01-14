(ns sysrev.views.reagent-json-view
  (:require [sysrev.views.html :refer [html]]
            [clojure.string :as st]))

(defn ns-str
  "Create a new namespace given input"
  [root & [leaf]]
  (let [namespace (clojure.string/trim (str root " " leaf))]
    (if (seq namespace)
      namespace
      "root")))

;; this is from https://github.com/yogthos/json-html
;; MIT License
(defn render-keyword [k]
  (str ":" (->> k ((juxt namespace name)) (remove nil?) (st/join "/"))))

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

(defn render-collection [col namespace & [on-click]]
  (if (empty? col)
    [:div.jh-type-object [:span.jh-empty-collection]]
    [:table.jh-type-object
     [:tbody
      (for [[i v] (map-indexed vector col)]
        ^{:key i} [:tr [:th.jh-key.jh-array-key
                        {:on-click #(on-click % {:ns (ns-str namespace i)})
                         :data-namespace (ns-str namespace i)} i]
                   [:td.jh-value.jh-array-value {:on-click (partial #(on-click % {:ns (ns-str namespace i)}))
                                                 :data-namespace (ns-str namespace i)} (render-html v (ns-str namespace i) on-click)]])]]))

(defn str-compare [k1 k2]
  (compare (str k1) (str k2)))

(defn sort-set [s]
  (try
    (into (sorted-set) s)
    (catch js/Error _
      (into (sorted-set-by str-compare) s))))

;; ns not handled properly yet
(defn render-set [s namespace & [on-click]]
  (if (empty? s)
    [:div.jh-type-set [:span.jh-empty-set]]
    [:ul (for [item (sort-set s)] [:li.jh-value {:on-click #(on-click % {:ns namespace})} (render-html item namespace)])]))

(defn sort-map [m]
  (let [m (mapv (fn [[k v]]
                  [(if (= (type k) js/Object) (js->clj k) k) v])
                m)]
    (try
      (into (sorted-map) m)
      (catch js/Error _
        (into (sorted-map-by str-compare) m)))))

(defn render-map [m namespace & [on-click]]
  (if (empty? m)
    [:div.jh-type-object [:span.jh-empty-map]]
    [:table.jh-type-object
     [:tbody
      (for [[k v] (sort-map m)]
        ^{:key k}
        [:tr [:th.jh-key.jh-object-key {:data-namespace (ns-str namespace)} (render-html k (ns-str namespace k) on-click)]
         [:td.jh-value.jh-object-value {:data-namespace (ns-str namespace k)} (render-html v (ns-str namespace k) on-click)]])]]))

(defn render-string [s namespace & [on-click]]
  [:span.jh-type-string {:on-click #(on-click % {:ns (ns-str namespace)})
                         :data-namespace (ns-str namespace)}
   (if (st/blank? s)
     [:span.jh-empty-string {:on-click #(on-click % {:ns (ns-str namespace)})}]
     (escape-html s))])

(defn render-html [v namespace & [on-click]]
  (let [namespace (or namespace "")
        t (type v)]
    (cond
      (satisfies? Render v) (render v)
      (= t Keyword) [:span.jh-type-string {:on-click #(on-click % {:ns namespace})} (render-keyword v)]
      (= t Symbol) [:span.jh-type-string {:on-click #(on-click % {:ns namespace})} (str v)]
      (= t js/String) #_[:span.jh-type-string (render-string v)]
      (render-string v namespace on-click)
      (= t js/Date) [:span.jh-type-date {:on-click #(on-click % {:ns namespace})} (.toString v)]
      (= t js/Boolean) [:span.jh-type-bool {:on-click #(on-click % {:ns namespace})} (str v)]
      (= t js/Number) [:span.jh-type-number {:on-click #(on-click % {:ns namespace})} v]
      (= t js/Array) (render-html (js->clj v) namespace on-click)
      (satisfies? IMap v) (render-map v namespace on-click)
      (satisfies? ISet v) (render-set v on-click)
      (satisfies? ICollection v) (render-collection v namespace on-click)
      (= t js/Object) (render-html (obj->clj v) namespace on-click)
      nil [:span.jh-empty nil])))

(defn edn->hiccup [edn]
  [:div.jh-root (render-html edn "")])

(defn edn->html [edn]
  (linkify-links (html (edn->hiccup edn))))

(defn json->hiccup [json & [on-click]]
  [:div.jh-root (render-html (js->clj json) "" on-click)])

(defn json->html [json & [on-click]]
  (linkify-links (html (json->hiccup json "" on-click))))

(defn ReactJSONView
  "JSON is the a json object. on-click is a function of context and event"
  [{:keys [json on-click]
    :or {on-click (fn [_event _context]
                    (.stopPropagation _event)
                    (.preventDefault _event)
                    (.log js/console "context: " (clj->js _context)))}}]
  (json->hiccup json on-click))
