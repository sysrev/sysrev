(ns sysrev.biosource.annotations
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]))

(def annotations-route "https://api.insilica.co/nlp/ner")

(defn get-annotations
  "Given a string of text, return a vector of annotation maps"
  [string]
  (-> (http/post annotations-route
                 {:content-type "application/json"
                  :body (json/write-str {:postData (-> string
                                                       (clojure.string/replace "\n" ""))})})
      :body
      (json/read-str :key-fn keyword)
      (->> (mapv #(assoc % :name (clojure.string/replace (:name %) #"\"" ""))))))

