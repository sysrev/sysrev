(ns sysrev.spark.similarity
  (:require
   [sysrev.spark.core :refer [df-query df->clj]]
   [clojure.math.numeric-tower :as math])
  (:import
   [org.apache.spark.ml UnaryTransformer]
   [org.apache.spark.ml.feature HashingTF IDF Tokenizer]
   [org.apache.spark.sql SQLContext SparkSession Dataset]))

(defn ^Dataset do-tokenizer [^Dataset df input-col output-col]
  (-> (Tokenizer.)
      (.setInputCol input-col)
      (.setOutputCol output-col)
      (.transform df)))

(defn ^Dataset do-hashing-tf [^Dataset df input-col output-col num-features]
  (-> (HashingTF.)
      (.setInputCol input-col)
      (.setOutputCol output-col)
      (.setNumFeatures num-features)
      (.transform df)))
