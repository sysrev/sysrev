(ns datapub.util
  (:require [com.walmartlabs.lacinia.resolve :as resolve]
            [next.jdbc :as jdbc]
            [sysrev.lacinia.interface :as sl]
            [sysrev.postgres.interface :as pg]))

(declare context-memos)

(defmacro with-tx-context
  "Either use an existing :tx in the context, or create a new transaction
  and assign it to :tx in the context."
  [[name-sym context] & body]
  `(let [context# ~context]
     (if-let [tx# (:tx context#)]
       (let [~name-sym context#] ~@body)
       (pg/retry-serial
        (merge {:n 0} (:tx-retry-opts context#))
        (jdbc/with-transaction [tx# (get-in context# [:pedestal :postgres :datasource])
                                {:isolation :serializable}]
          (let [context# (assoc context# :tx tx#)
                ~name-sym (assoc context# :memos (context-memos context#))]
            ~@body))))))

(defn execute! [context sqlmap]
  (pg/execute! (:tx context) sqlmap))

(defn execute-one! [context sqlmap]
  (pg/execute-one! (:tx context) sqlmap))

(defn plan [context sqlmap]
  (pg/plan (:tx context) sqlmap))

(defn sysrev-dev? [context]
  (let [auth (-> context :request :headers (get "authorization")
                 (or (-> context
                         :com.walmartlabs.lacinia/connection-params
                         :authorization)))
        sysrev-dev-key (-> context :pedestal :config :secrets :sysrev-dev-key)]
    (and sysrev-dev-key (= auth (str "Bearer " sysrev-dev-key)))))

(defmacro ensure-sysrev-dev [context & body]
  `(if (sysrev-dev? ~context)
     (do ~@body)
     (throw (RuntimeException. "Unauthorized."))))

(defn connection-helper
  "Helper to resolve GraphQL Cursor Connections as specified by
  https://relay.dev/graphql/connections.htm

  Look at list-datasets for an example implementation."
  [context {first* :first :keys [after]} {:keys [count-f edges-f]}]
  (let [cursor (if (empty? after) 0 (parse-long after))]
    (if (and after (not (and cursor (nat-int? cursor))))
      (resolve/resolve-as nil {:message "Invalid cursor."
                               :cursor after})
      (with-tx-context [context (assoc context :tx-retry-opts {:n 1})]
        (let [ks (sl/current-selection-names context)
              ct (when (:totalCount ks)
                   (count-f {:context context}))
              limit (inc (min 100 (or first* 100)))
              [edges more] (when (and (or (nil? first*) (pos? first*))
                                      (or (:edges ks) (:pageInfo ks)))
                             (edges-f {:context context
                                       :cursor cursor
                                       :limit limit}))]
          {:edges edges
           :pageInfo
           {:endCursor (:cursor (last edges))
            :hasNextPage (boolean (seq more))
              ;; The spec allows hasPreviousPage to return true when unknown.
            :hasPreviousPage (not (or (zero? cursor) (= ct (count edges))))
            :startCursor (:cursor (first edges))}
           :totalCount ct})))))

(defn public-dataset? [context ^Long int-id]
  (-> context
      (execute-one! {:select :public :from :dataset :where [:= :id int-id]})
      :dataset/public
      boolean))

(defn context-memos [context]
  {:public-dataset? (memoize (partial public-dataset? context))})
