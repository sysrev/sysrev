(ns sysrev.util-lite.core
  (:require
   [clojure.tools.logging :as log]))

(defn full-name ^String [x]
  (cond
    (nil? x) nil
    (string? x) x
    (simple-ident? x) (name x)
    (ident? x) (str (namespace x) "/" (name x))))

(def retry-recur-val ::retry-recur)

(defmacro retry [{:keys [interval-ms n throw-pred]} & body]
  `(let [throw-pred# (or ~throw-pred (constantly false))]
     (loop [interval-ms# ~interval-ms
            n# ~n]
     ;; Can't recur from inside the catch, so we use a special return
     ;; value to signal the need to recur.
       (let [ret#
             (try
               ~@body
               (catch Exception e#
                 (if (and (pos? n#) (not (throw-pred# e#)))
                   (do
                     (log/info e# "Retrying after" interval-ms# "ms due to Exception")
                     retry-recur-val)
                   (throw e#))))]
         (if (= ret# retry-recur-val)
           (do
             (Thread/sleep interval-ms#)
             (recur (+ interval-ms# interval-ms# (.longValue ^Integer (rand-int 100))) (dec n#)))
           ret#)))))

(defn wait-timeout [pred & {:keys [timeout-f timeout-ms]}]
  {:pre [(fn? pred) (fn? timeout-f) (number? timeout-ms)]}
  (let [start (System/nanoTime)
        timeout-ns (* timeout-ms 1000000)]
    (loop []
      (let [result (pred)]
        (cond
          result result
          (< timeout-ns (- (System/nanoTime) start)) (timeout-f)
          :else (recur))))))
