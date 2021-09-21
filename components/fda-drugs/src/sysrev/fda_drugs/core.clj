(ns sysrev.fda-drugs.core
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [medley.core :as medley]
            [sysrev.file-util.interface :as file-util])
  (:import (java.net URL)
           (java.nio.file Path)))

(set! *warn-on-reflection* true)

(def data-url "https://www.fda.gov/media/89850/download")

(defn download-data! [^Path path]
  (with-open [input-stream (.openStream (URL. data-url))]
    (file-util/copy! input-stream path #{:replace-existing})))

(defn parse-data [^Path path]
  (->> path file-util/read-zip-entries
       (medley/map-vals
        (fn [bytes]
          (let [rows (-> bytes io/reader (csv/read-csv :separator \tab))
                header (map keyword (first rows))]
            (map #(zipmap header %) (rest rows)))))))
