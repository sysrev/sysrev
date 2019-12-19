(ns sysrev.data.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer-macros [defn-spec]]
            [re-frame.core :refer [dispatch reg-sub reg-event-db reg-event-fx
                                   trim-v reg-fx]]
            [re-frame.db :refer [app-db]]
            [sysrev.loading :as loading]
            [sysrev.ajax :refer [reg-event-ajax-fx run-ajax]]
            [sysrev.shared.util :as sutil :refer [in? dissoc-in]]))

(defonce
  ^{:doc "Holds static definitions for data items fetched from server"}
  data-defs (atom {}))

;; re-frame db value
(s/def ::db map?)

;; data item vector
(s/def ::item-name keyword?) ; keyword name for item
(s/def ::item-arg (constantly true)) ; any value
;; ex: [:project 100]
(s/def ::item (s/and vector? (s/cat :name ::item-name :args (s/* ::item-arg))))

;; item value formats
(s/def ::item-args (s/coll-of ::item-arg))
(s/def ::db-item-args (s/cat :db ::db :args (s/* ::item-arg)))

;; def-data arguments
(doseq [arg [::loaded? ::uri ::process ::prereqs ::content ::on-error]]
  (s/def arg ifn?))
(s/def ::method keyword?)
(s/def ::content-type string?)

(defn-spec def-data map?
  "Create a definition for a data item to fetch from server.

  Required parameters:

  `:uri` - fn taking `::item-args`, returns url string for request.

  `:loaded?` - fn taking `::db-item-args`, returns boolean indicating
  whether item is already loaded. Should check db for the presence of
  entries stored by the `:process` or `:on-error` handlers. This
  affects the behavior of `:require` and `:reload`; `:require` will
  fetch only when item is not loaded, and `:reload` will fetch only
  when item is loaded. Items will be loaded again automatically if the
  item becomes not-loaded (data entry removed from db) after
  `:require` was called previously.

  `:process` - (fn [cofx item-args result] ...)
  * `cofx` is normal re-frame cofx map for reg-event-fx
    (ex: {:keys [db]}).
  * `item-args` provides the `::item-args` from the item vector passed to
    one of `:require` `:reload` `:fetch`.
  * `result` provides the value returned from server on HTTP success.
    The value is unwrapped, having already been extracted from the raw
    {:result value, ...} returned by the server.

  Optional parameters:

  `:prereqs` - fn taking `::item-args`, returns vector of `::item`
  entries. The prereq items must be loaded before this item will be
  fetched; if loading an item is prevented due to missing prereqs, it
  will be loaded automatically when the prereqs are satisfied. If not
  specified, [[:identity]] will be used as default value.

  `:content` - fn taking `::item-args`, returns request content
  (normally a map of request parameter values).

  `:method` - keyword value for HTTP method (default :get)

  `:content-type` - string value for HTTP Content-Type header

  `:on-error` - Similar to `:process` but called on HTTP error
  status. cofx value includes an `:error` key, which is taken from
  the `:error` field of the server response."
  [name ::item-name &
   {:keys [uri loaded? prereqs content process on-error method content-type]
    :or {method :get, prereqs (constantly [[:identity]])}
    :as fields}
   (s/? (s/keys* :req-un [::uri ::loaded? ::process]
                 :opt-un [::prereqs ::content ::on-error ::method ::content-type]))]
  (s/assert ::item-name name)
  (s/assert ::uri uri)
  (s/assert ::loaded? loaded?)
  (s/assert ::process process)
  (s/assert ::prereqs prereqs)
  (when content (s/assert ::content content))
  (when content-type (s/assert ::content-type content-type))
  (when on-error (s/assert ::on-error on-error))
  (when method (s/assert ::method method))
  (swap! data-defs assoc name
         (merge fields {:prereqs prereqs :method method})))

;; Gets raw list of data requirements
(defn- get-needed-raw [db] (get db :needed []))

;; Adds an item to list of requirements
(defn- require-item
  ([db prereqs item]
   (let [entry [(vec prereqs) item]]
     (if (in? (:needed db) entry)
       db
       (update db :needed
               #(-> % vec (conj entry) distinct vec))))))

;; Register `item` as required; will trigger fetching from server
;; immediately or after any prerequisites for `item` have been loaded.
(reg-event-fx :require [trim-v]
              (fn [{:keys [db]} [item]]
                (let [[name & args] item
                      entry (get @data-defs name)
                      prereqs (some-> (:prereqs entry) (apply args))
                      new-db (require-item db prereqs item)
                      changed? (not= (:needed new-db) (:needed db))]
                  (cond-> {:db new-db
                           :dispatch-n (->> prereqs (map (fn [x] [:require x])))}
                    changed? (merge {::fetch-if-missing item})))))

(defn- add-load-trigger [db item trigger-id action]
  (assoc-in db [:on-load item trigger-id] action))

(defn- remove-load-triggers [db item]
  (dissoc-in db [:on-load item]))

(defn- lookup-load-triggers [db item]
  (get-in db [:on-load item]))

(reg-event-db :data/after-load [trim-v]
              (fn [db [item trigger-id action]]
                (add-load-trigger db item trigger-id action)))

(reg-event-fx ::process-load-triggers [trim-v]
              (fn [{:keys [db]} [item]]
                (let [actions (vals (lookup-load-triggers db item))]
                  (doseq [action actions]
                    (cond (vector? action)  nil
                          (fn? action)      (action)
                          (seq? action)     (doseq [subaction action]
                                              (when (fn? subaction)
                                                (subaction)))))
                  {:db (remove-load-triggers db item)
                   :dispatch-n (->> actions
                                    (map #(cond (vector? %)  (list %)
                                                (seq? %)     (filter vector? %)
                                                (fn? %)      nil))
                                    (apply concat))})))

(reg-fx ::process-load-triggers
        (fn [item] (dispatch [::process-load-triggers item])))

(reg-event-db :data/reset-required #(assoc % :needed []))

(reg-fx :data/reset-required (fn [_] (dispatch [:data/reset-required])))

;; Tests if `item` is loaded
(defn- have-item? [db item]
  (let [[name & args] item
        {:keys [loaded?] :as _entry} (get @data-defs name)]
    (apply loaded? db args)))
(reg-sub :have? (fn [db [_ item]] (have-item? db item)))

;; Returns list of required items with no missing prerequisites
(defn- get-needed-items [db]
  (->> (get-needed-raw db)
       (mapv (fn [[prereqs item]]
               (when (every? #(have-item? db %) prereqs)
                 item)))
       (filterv (comp not nil?))))

;; Returns list of required items that are not yet loaded
(defn- get-missing-items [db]
  (->> (get-needed-items db)
       (remove (partial have-item? db))
       vec))
(reg-sub ::missing get-missing-items)

;; Tests whether all required data has been loaded
(reg-sub :data/ready?
         :<- [:initialized?]
         :<- [::missing]
         (fn [[initialized? missing]]
           (boolean (and initialized? (empty? missing)))))

;; TODO: replace this with a queue for items to fetch in @app-db
(defonce
  ^{:doc "Atom used to ensure a minimal delay between :fetch events."}
  last-fetch-millis (atom 0))

;; Fetch data item from server, unless identical request is pending.
;;
;; Usually this should be triggered from :require via `with-loader`,
;; or from :reload to fetch updated data in response to a user action.
(reg-event-fx :fetch [trim-v]
              (fn [{:keys [db]} [item]]
                (let [[name & args] item
                      {:keys [uri content content-type method]
                       :as entry} (get @data-defs name)
                      elapsed-millis (- (js/Date.now) @last-fetch-millis)]
                  (assert entry (str "def-data not found - " (pr-str name)))
                  (when-not (loading/item-loading? item)
                    (cond (loading/item-spammed? item)
                          {:data-failed item}

                          (< elapsed-millis 10)
                          {:dispatch-later [{:dispatch [:fetch item] :ms (- 15 elapsed-millis)}]}

                          (not (loading/ajax-action-inactive?))
                          {:dispatch-later [{:dispatch [:fetch item] :ms 10}]}

                          :else
                          (let [content-val (some-> content (apply args))]
                            (reset! last-fetch-millis (js/Date.now))
                            (merge {:data-sent item}
                                   (run-ajax
                                    (cond-> {:db db
                                             :method method
                                             :uri (apply uri args)
                                             :on-success [::on-success item]
                                             :on-failure [::on-failure item]
                                             :content-type (or content-type
                                                               "application/transit+json")}
                                      content-val (assoc :content content-val))))))))))

(reg-event-ajax-fx
 ::on-success
 (fn [{:keys [db] :as cofx} [item result]]
   (let [[name & args] item
         spammed? (loading/item-spammed? item)]
     (merge {:data-returned item}
            (if spammed?
              {:data-failed item}
              {:reset-data-failed item})
            (when (not spammed?)
              (when-let [entry (get @data-defs name)]
                (when-let [process (:process entry)]
                  (merge (apply process [cofx args result])
                         ;; Run :fetch-missing in case this request provided any
                         ;; missing data prerequisites.
                         {:fetch-missing [true item]}
                         (when (not-empty (lookup-load-triggers db item))
                           {::process-load-triggers item})))))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge {:data-returned item
             :data-failed item}
            (when-let [entry (get @data-defs name)]
              (if-let [process (:on-error entry)]
                (apply process [cofx args result])
                (do (js/console.error (str "data error: item = " (pr-str item)
                                           "\nerror: " (pr-str (:error cofx))))
                    {})))))))

;; Reload data item from server if already loaded.
(reg-event-fx :reload [trim-v]
              (fn [{:keys [db]} [item]]
                (when (and (or (have-item? db item)
                               (loading/item-failed? item))
                           (not (loading/item-loading? item)))
                  {:dispatch [:fetch item]})))

;; Dispatch both `:require` and `:reload`
(reg-event-fx :data/load [trim-v]
              (fn [_ [item]] {:dispatch-n [[:require item] [:reload item]]}))

(reg-fx ::fetch-if-missing
        (fn [item]
          (js/setTimeout #(let [db @app-db
                                missing (get-missing-items db)]
                            (when (and item (in? missing item)
                                       (not (loading/item-failed? item))
                                       (not (loading/item-loading? item)))
                              (dispatch [:fetch item])))
                         5)))

;; Fetches any missing required data
(reg-event-fx :fetch-missing
              (fn [{:keys [db]}]
                {:dispatch-n
                 (->> (get-missing-items db)
                      (remove #(loading/item-failed? %))
                      (remove #(loading/item-loading? %))
                      (mapv (fn [item] [:fetch item])))}))

(reg-fx :fetch-missing
        (fn [[fetch? trigger-item]]
          (when fetch?
            ;; Use setTimeout to ensure that changes to app-db from any simultaneously
            ;; dispatched events will have completed first.
            (js/setTimeout #(dispatch [:fetch-missing trigger-item]) 10))))

(defn init-data []
  (dispatch [:ui/load-default-panels])
  (dispatch [:fetch [:identity]]))
