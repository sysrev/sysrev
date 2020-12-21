(ns sysrev.biosource.annotations
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [sysrev.biosource.core :refer [api-host]]
            [sysrev.util :as util]))

(def annotations-route (str api-host "nlp/ner"))

(defn get-annotations
  "Given a string of text, return a vector of annotation maps"
  [string]
  (try (when-not (str/blank? string)
         (-> (http/post annotations-route
                        {:content-type "application/json"
                         :body (json/write-str {:postData (-> string (str/replace "\n" ""))})})
             :body
             (util/read-json)
             (->> (mapv #(assoc % :name (str/replace (:name %) #"\"" ""))))))
       (catch Throwable _
         (log/warn (str "error loading annotations from " annotations-route) ))))
