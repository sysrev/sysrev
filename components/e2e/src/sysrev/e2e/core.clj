(ns sysrev.e2e.core
  (:require [clojure.test :as test]
            [etaoin.api :as ea]
            [slingshot.slingshot :refer [try+]]))

(defn test-server-fixture [f]
  (f))

;; For REPL eval
#_:clj-kondo/ignore
(defn run-headless? [] false)
#_:clj-kondo/ignore
(defn run-headless? [] true)

(defmacro with-driver [driver-sym & body]
  `(ea/with-driver :chrome {:headless (run-headless?)
                            :size [1600 1000]}
     ~driver-sym
     ~@body))

(defmacro doto-driver [driver-sym & body]
  `(with-driver ~driver-sym
     (doto ~driver-sym
       ~@body)))

#_:clj-kondo/ignore
(defn wait-is-visible? [driver q]
  (try+
   (ea/wait-visible driver q)
   (catch [:type :etaoin/timeout] _
       nil))
  (test/is (ea/visible? driver q)))
