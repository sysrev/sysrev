(ns sysrev.action.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer-macros [defn-spec]]
            [re-frame.core :refer [reg-event-fx trim-v]]
            [sysrev.ajax :refer [reg-event-ajax-fx run-ajax]]
            [sysrev.loading :as loading]))

(defonce
  ^{:doc "Holds static definitions for server request actions"}
  action-defs (atom {}))

;; re-frame db value
(s/def ::db map?)

;; action item vector
(s/def ::item-name keyword?) ; keyword name for item
(s/def ::item-arg (constantly true)) ; any value
;; ex: [:join-project 100]
(s/def ::item (s/and vector? (s/cat :name ::item-name :args (s/* ::item-arg))))

;; item value formats
(s/def ::item-args (s/coll-of ::item-arg))

;; def-data arguments
(doseq [arg [::uri ::content ::process ::on-error]]
  (s/def arg ifn?))
(s/def ::method keyword?)
(s/def ::content-type string?)

(defn-spec def-action map?
  "Create a definition for a server request. `sysrev.data.core/def-data`
  should generally be used instead if the main purpose of the request is
  to fetch data from the server and save it to client state.

  Required parameters:

  `:uri` - fn taking `::item-args`, returns url string for request.

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

  `:content-type` - string value for HTTP Content-Type header

  `:on-error` - Similar to `:process` but called on HTTP error
  status. cofx value includes an `:error` key, which is taken from
  the `:error` field of the server response."
  [name ::item-name &
   {:keys [uri content process on-error method content-type]
    :or {method :post}
    :as fields}
   (s/? (s/keys* :req-un [::uri ::process]
                 :opt-un [::content ::on-error ::method ::content-type]))]
  (s/assert ::item-name name)
  (s/assert ::uri uri)
  (s/assert ::process process)
  (when content (s/assert ::content content))
  (when content-type (s/assert ::content-type content-type))
  (when on-error (s/assert ::on-error on-error))
  (when method (s/assert ::method method))
  (swap! action-defs assoc name
         (merge fields {:method method})))

;; Runs an AJAX action specified by `item`
(reg-event-fx
 :action [trim-v]
 (fn [{:keys [db]} [item]]
   (let [[name & args] item
         {:keys [uri content content-type method]
          :as entry} (get @action-defs name)
         content-val (some-> content (apply args))]
     (assert entry (str "def-action not found - " (pr-str name)))
     (cond (not (loading/ajax-status-inactive?))
           {:dispatch-later [{:dispatch [:action item] :ms 10}]}
           :else
           (merge {:action-sent item}
                  (run-ajax (cond-> {:db db
                                     :method method
                                     :uri (apply uri args)
                                     :on-success [::on-success item]
                                     :on-failure [::on-failure item]
                                     :content-type (or content-type "application/transit+json")}
                              content-val (assoc :content content-val))))))))

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
