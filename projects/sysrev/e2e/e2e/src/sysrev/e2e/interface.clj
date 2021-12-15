(ns sysrev.e2e.interface
  (:require [sysrev.e2e.core :as e2e]))

(defn get-path [driver]
  (e2e/get-path driver))

(defn log-in-as [driver email]
  (e2e/log-in-as driver email))

(defn path [& args]
  (apply e2e/path args))

(defn test-server-fixture [f]
  (e2e/test-server-fixture f))

(defmacro doto-driver [driver-sym & body]
  `(e2e/doto-driver ~driver-sym ~@body))

(defn wait-is-visible? [driver q]
  (e2e/wait-is-visible? driver q))

(defmacro with-driver [driver-sym & body]
  `(e2e/with-driver ~driver-sym ~@body))
