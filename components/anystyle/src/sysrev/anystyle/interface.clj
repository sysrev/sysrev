(ns sysrev.anystyle.interface
  "Finds and parses bibliographic references in text"
  (:require [clj-http.client :as http]))

(defn parse-str
  "Returns map of bibliographic references parsed from
   the text of s."
  [api-base ^String s]
  (->> {:as :json
        :content-type :json
        :form-params {:text s}}
       (http/post (str api-base "/v1/parse"))
       :body))
