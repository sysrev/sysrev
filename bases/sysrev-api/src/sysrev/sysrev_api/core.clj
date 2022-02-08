(ns sysrev.sysrev-api.core
  (:require
   [next.jdbc :as jdbc]
   [sysrev.postgres.interface :as pg]))

(def re-int-id #"[1-9][0-9]*")

(defmacro with-tx-context
  "Either use an existing :tx in the context, or create a new transaction
  and assign it to :tx in the context."
  [[name-sym context] & body]
  `(let [context# ~context]
     (if-let [tx# (::tx context#)]
       (let [~name-sym context#] ~@body)
       (jdbc/with-transaction [tx# (get-in context# [:pedestal :postgres :datasource])
                               {:isolation :serializable}]
         (let [~name-sym (assoc context# ::tx tx#)]
           ~@body)))))

(defn execute! [context sqlmap]
  (pg/execute! (::tx context) sqlmap))

(defn execute-one! [context sqlmap]
  (pg/execute-one! (::tx context) sqlmap))

(defn plan [context sqlmap]
  (pg/plan (::tx context) sqlmap))

(defn inv-cols [m]
  (reduce (fn [m [k v]] (assoc m v k)) {} m))
