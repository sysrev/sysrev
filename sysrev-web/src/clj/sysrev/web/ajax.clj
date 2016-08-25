(ns sysrev.web.ajax
  (:require [clojure.data.json :as json]
            [ring.util.response :as r]
            [sysrev.db.articles :as articles]))

(defn wrap-json
  "Create an HTTP response with content of OBJ converted to a JSON string."
  [obj]
  (-> obj
      (json/write-str)
      (r/response)
      (r/header "Content-Type" "application/json; charset=utf-8")))

(defn web-criteria []
  (let [cs (articles/all-criteria)]
    (->> cs
         (map #(vector (:criteria_id %) (dissoc % :criteria_id)))
         (apply concat)
         (apply hash-map))))
