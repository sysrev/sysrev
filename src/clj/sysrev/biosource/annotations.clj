(ns sysrev.biosource.annotations
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [sysrev.biosource.core :refer [api-host]]))

(def annotations-route (str api-host "nlp/ner"))

(defn get-annotations
  "Given a string of text, return a vector of annotation maps"
  [string]
  (try
    (if (clojure.string/blank? string)
      nil
      (-> (http/post annotations-route
                     {:content-type "application/json"
                      :body (json/write-str {:postData (-> string
                                                           (clojure.string/replace "\n" ""))})})
          :body
          (json/read-str :key-fn keyword)
          (->> (mapv #(assoc % :name (clojure.string/replace (:name %) #"\"" ""))))))
    (catch Throwable e
      (log/info (str "error loading annotations from " annotations-route) )
      nil)))
