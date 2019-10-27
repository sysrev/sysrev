(ns sysrev.web.build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn read-build-id []
  (try (-> (io/resource "git-commit") slurp str/split-lines first)
       (catch Throwable _ nil)))

(defn read-build-time []
  (try (-> (io/resource "build-time") slurp str/split-lines first)
       (catch Throwable _ nil)))

(def build-id (read-build-id))
(def build-time (read-build-time))
