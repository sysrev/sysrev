(ns sysrev.web.build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sysrev.util :refer [ignore-exceptions]]))

(defn read-build-id []
  (ignore-exceptions
   (-> (io/resource "git-commit") slurp str/split-lines first)))

(defn read-build-time []
  (ignore-exceptions
   (-> (io/resource "build-time") slurp str/split-lines first)))

(def build-id (read-build-id))
(def build-time (read-build-time))
