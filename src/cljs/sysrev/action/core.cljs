(ns sysrev.action.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer-macros [defn-spec]]
            [reagent.core :as r]
            [re-frame.core :refer [reg-event-fx trim-v dispatch]]
            [sysrev.ajax :refer [reg-event-ajax-fx run-ajax]]
            [sysrev.loading :as loading]
            [sysrev.util :as util :refer [apply-keyargs]]))

(defonce
  ^{:doc "Holds static definitions for server request actions"}
  action-defs (r/cursor loading/ajax-db [:action]))

(defn running?
  "Tests if any AJAX action request matching `query` is currently pending.
  `ignore` is an optional query value to exclude matching requests."
  ([]
   (running? nil))
  ([query & {:keys [ignore] :as args}]
   (apply-keyargs #'loading/action-running? query args)))

;; action item vector
(s/def ::item-name keyword?) ; keyword name for item

;; def-action arguments
(doseq [arg [::content ::process ::on-error]]
  (s/def arg ifn?))
(s/def ::uri (s/or :fn fn? :string string?))
(s/def ::method keyword?)
(s/def ::content-type string?)
(s/def ::timeout number?)
(s/def ::hide-loading boolean?)

(defn-spec def-action map?
  "Create a definition for a server request. `sysrev.data.core/def-data`
  should generally be used instead if the main purpose of the request is
  to fetch data from the server and save it to client state.

  Required parameters:

  `:uri` - fn taking `::item-args`, returns url string for request.
  `:uri` can also be passed as a string value.

  `:process` - (fn [cofx item-args result] ...)
  * `cofx` is normal re-frame cofx map for reg-event-fx
    (ex: {:keys [db]}).
  * `item-args` provides the `::item-args` from the item vector passed to
    one of `:require` `:reload` `:fetch`.
  * `result` provides the value returned from server on HTTP success.
    The value is unwrapped, having already been extracted from the raw
    {:result value, ...} returned by the server.

  Optional parameters:

  `:content` - fn taking `::item-args`, returns request content
  (normally a map of request parameter values).

  `:method` - keyword value for HTTP method (default :post)

  `:timeout` - seconds until ajax call times out. defaults to 2 minutes.

  `:content-type` - string value for HTTP Content-Type header

  `:on-error` - Similar to `:process` but called on HTTP error
  status. cofx value includes an `:error` key, which is taken from
  the `:error` field of the server response.

  `:hide-loading` - If true, don't render global loading indicator while
  this request is pending."
  [name ::item-name &
   {:keys [uri content process on-error method content-type timeout hide-loading]
    :or {method :post timeout (* 2 60 1000)}
    :as fields}
   (s/? (s/keys* :req-un [::uri ::process]
                 :opt-un [::content ::on-error ::method ::content-type ::hide-loading]))]
  (s/assert ::item-name name)
  (s/assert ::uri uri)
  (s/assert ::process process)
  (when (some? content)      (s/assert ::content content))
  (when (some? content-type) (s/assert ::content-type content-type))
  (when (some? on-error)     (s/assert ::on-error on-error))
  (when (some? method)       (s/assert ::method method))
  (when (some? timeout)      (s/assert ::timeout timeout))
  (when (some? hide-loading) (s/assert ::hide-loading hide-loading))
  (swap! action-defs assoc name
         (-> (merge fields {:method method :timeout timeout})
             (update :uri #(cond-> % (string? %) (constantly))))))

;; Runs an AJAX action specified by `item`
(reg-event-fx
 :action [trim-v]
 (fn [{:keys [db]} [item]]
   (let [[name & args] item
         {:keys [uri content content-type method timeout]
          :as entry} (get @action-defs name)
         content-val (some-> content (apply args))]
     (assert entry (str "def-action not found - " (pr-str name)))
     (if (loading/ajax-status-inactive?)
       (-> (run-ajax
            (cond-> {:db db
                     :method method
                     :uri (apply uri args)
                     :on-success [::on-success item]
                     :on-failure [::on-failure item]
                     :content-type (or content-type "application/transit+json")
                     :timeout timeout}
              content-val (assoc :content content-val)))
           (merge {:action-sent item}))
       {:dispatch-later [{:dispatch [:action item] :ms 10}]}))))

(reg-event-ajax-fx
 ::on-success
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge {:action-returned item}
            (when-let [entry (get @action-defs name)]
              (when-let [process (:process entry)]
                (apply process [cofx args result])))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge {:action-returned item}
            (when-let [entry (get @action-defs name)]
              (if-let [process (:on-error entry)]
                (apply process [cofx args result])
                (do (js/console.error (str "action error: item = " (pr-str item)
                                           "\nerror: " (pr-str (:error cofx))))
                    {})))))))

(defn run-action [& item]
  (dispatch [:action (into [] item)]))
