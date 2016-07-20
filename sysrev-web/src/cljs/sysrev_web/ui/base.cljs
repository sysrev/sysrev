(ns sysrev-web.ui.base
  (:require [clojure.string :refer [split join]]
            [goog.string :refer [unescapeEntities]]))

(def nbsp (unescapeEntities "&nbsp;"))

(defn url-domain' [url]
  (-> url
      (split "//")
      second
      (split "/")
      first
      (split ".")
      (->>
        (take-last 2)
        (join "."))))

(defn url-domain [url]
  "Gets the example.com part of a url"
  (let [res (url-domain' url)]
    (if (string? res) res url)))

(defn out-link [url]
  [:a.item {:target "_blank" :href url}
   (url-domain url)
   nbsp
   [:i.external.icon]])
