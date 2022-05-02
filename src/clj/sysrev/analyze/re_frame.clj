(ns sysrev.analyze.re-frame
  "Uses clj-kondo api to find unused re-frame definitions.

  Adapted from:
    roman01la/analyze.clj
    https://gist.github.com/roman01la/c6a2e4db8d74f89789292002794a7142"
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.set :as set]))

;; checks re-frame's :<- syntax
;; to mark dependency subscriptions as used
(def ^:private analyze-reg-sub
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ kw & children] (:children node)
          reg-sub-kw (api/reg-keyword! kw 're-frame.core/reg-sub)
          reg-dep-kw! #(-> % :children first (api/reg-keyword! 're-frame.core/subscribe))
          sub-kws (map #(if (api/vector-node? %) (reg-dep-kw! %) %)
                       (butlast children))]
      {:node (api/list-node
               `(do ~reg-sub-kw ~@sub-kws ~(last children)))}))")

(def ^:private analyze-reg-event-db
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ kw & children] (:children node)
          reg-event-db-kw (api/reg-keyword! kw 're-frame.core/reg-event-db)]
      {:node (api/list-node
               `(do ~reg-event-db-kw ~(last children)))}))")

(def ^:private analyze-reg-event-fx
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ kw & children] (:children node)
          reg-event-fx-kw (api/reg-keyword! kw 're-frame.core/reg-event-fx)]
      {:node (api/list-node
               `(do ~reg-event-fx-kw ~(last children)))}))")

(def ^:private analyze-def-data
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ kw & children] (:children node)
          def-data-kw (api/reg-keyword! kw 'sysrev.data.core/def-data)]
      {:node (api/list-node
               `(do ~def-data-kw ~@children))}))")

(def ^:private analyze-def-action
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ kw & children] (:children node)
          def-action-kw (api/reg-keyword! kw 'sysrev.action.core/def-action)]
      {:node (api/list-node
               `(do ~def-action-kw ~@children))}))")

;; requires subscribtion name (keyword)
;; to be statically defined at the call site
;; i.e. `(subscribe [:my/sub])`
(def ^:private analyze-subscribe
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ query] (:children node)
          [kw & children] (:children query)]
      {:node (api/list-node
               `(do ~(api/reg-keyword! kw 're-frame.core/subscribe)
                    ~@children))}))")

(def ^:private analyze-dispatch
  "(require '[clj-kondo.hooks-api :as api])
  (fn [{node :node}]
    (let [[_ query] (:children node)
          [kw & children] (:children query)]
      {:node (api/list-node
               `(do ~(api/reg-keyword! kw 're-frame.core/dispatch)
                    ~@children))}))")

(defn ^:repl analyze-source []
  (clj-kondo/run!
   {:lint ["src/" "components/"]
    :config {:output {:analysis {:keywords true}}
             :hooks {:__dangerously-allow-string-hooks__ true
                     :analyze-call {'re-frame.core/reg-sub analyze-reg-sub
                                    're-frame.core/subscribe analyze-subscribe
                                    're-frame.core/reg-event-db analyze-reg-event-db
                                    're-frame.core/reg-event-fx analyze-reg-event-fx
                                    're-frame.core/dispatch analyze-dispatch
                                    'sysrev.data.core/def-data analyze-def-data
                                    'sysrev.action.core/def-action analyze-def-action}}}}))

(defn- get-keywords-usages [out var-name]
  (->> (:analysis out)
       :keywords
       (filter #(= var-name (:reg %)))
       (map #(assoc % :kw (if (:ns %)
                            (keyword (str (:ns %)) (:name %))
                            (keyword (:name %)))))))

(defn- find-unused-keywords [defs usages]
  (let [defs-set (into #{} (map :kw) defs)
        usages-set (into #{} (map :kw) usages)
        unused (set/difference defs-set usages-set)]
    (filter (comp unused :kw) defs)))

(defn- find-usage-of-undefined-keywords [defs usages]
  (let [defs-set (into #{} (map :kw) defs)
        usages-set (into #{} (map :kw) usages)
        undefined (set/difference usages-set defs-set)]
    (filter (comp undefined :kw) defs)))

(defn- fmt-message [warning-type {:keys [kw filename row]}]
  (-> (case warning-type
        :unused-subscription "Unused subscription"
        :unused-event "Unused event"
        :undefined-subscription "Undefined subscription"
        :undefined-event "Dispatch to undefined event")
      (str " " kw " at line " row  " in " filename)))

(defn ^:repl print-unused-reg-sub []
  (let [out (analyze-source)
        defs (get-keywords-usages out 're-frame.core/reg-sub)
        usages (concat (get-keywords-usages out 're-frame.core/subscribe)
                       ;; treat matching def-data or def-action on kw as usage
                       (get-keywords-usages out 'sysrev.data.core/def-data)
                       (get-keywords-usages out 'sysrev.action.core/def-action)
                       ;; match on nil - treat generic occurences of kw as usage.
                       (get-keywords-usages out nil))
        unused (find-unused-keywords defs usages)
        _undefined (find-usage-of-undefined-keywords
                    defs (get-keywords-usages out 're-frame.core/subscribe))]
    (doseq [sub unused]
      (println (fmt-message :unused-subscription sub)))
    #_(doseq [sub _undefined]
        (println (fmt-message :undefined-subscription sub)))))

(defn ^:repl print-unused-reg-event []
  (let [out (analyze-source)
        defs (concat (get-keywords-usages out 're-frame.core/reg-event-db)
                     (get-keywords-usages out 're-frame.core/reg-event-fx))
        usages (concat (get-keywords-usages out 're-frame.core/dispatch)
                       ;; treat matching def-data or def-action on kw as usage
                       (get-keywords-usages out 'sysrev.data.core/def-data)
                       (get-keywords-usages out 'sysrev.action.core/def-action)
                       ;; match on nil - treat generic occurences of kw as usage.
                       (get-keywords-usages out nil))
        unused (find-unused-keywords defs usages)
        _undefined (find-usage-of-undefined-keywords
                    defs (get-keywords-usages out 're-frame.core/dispatch))]
    (doseq [event unused]
      (println (fmt-message :unused-event event)))
    #_(doseq [event _undefined]
        (println (fmt-message :undefined-event event)))))

(comment
  (do
    (print-unused-reg-sub)
    (print-unused-reg-event)))
