(ns sysrev.util-lite.core
  (:require
   [clojure.tools.logging :as log]))

(def retry-recur-val ::retry-recur)

(defmacro retry [{:keys [interval-ms n]} & body]
  `(loop [interval-ms# ~interval-ms
          n# ~n]
     ;; Can't recur from inside the catch, so we use a special return
     ;; value to signal the need to recur.
     (let [ret#
           (try
             ~@body
             (catch Exception e#
               (if (pos? n#)
                 (do
                   (log/info e# "Retrying after" interval-ms# "ms due to Exception")
                   retry-recur-val)
                 (throw e#))))]
       (if (= ret# retry-recur-val)
         (do
           (Thread/sleep interval-ms#)
           (recur (+ interval-ms# interval-ms# (.longValue ^Integer (rand-int 100))) (dec n#)))
         ret#))))
