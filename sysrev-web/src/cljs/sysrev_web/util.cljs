(ns sysrev-web.util
  (:require [pushy.core :as pushy]
            [sysrev-web.base :refer [history]]
            [clojure.string :refer [split join]]
            [goog.string :refer [unescapeEntities]]))

(defn scroll-top []
  (. js/window (scrollTo 0 0)))

(defn nav
  "Change the current route."
  [route]
  (pushy/set-token! history route))

(defn nav-scroll-top
  "Change the current route then scroll to top of page."
  [route]
  (pushy/set-token! history route)
  (scroll-top))

;; Slavish copy from stack overflow, get the position of occurence of regex, and the match.
;; Modified though to use a sorted map so we can have the result sorted by index.
;; https://stackoverflow.com/questions/18735665/how-can-i-get-the-positions-of-regex-matches-in-clojurescript
(defn re-pos [re s]
  (let [re (js/RegExp. (.-source re) "g")]
    (loop [res (sorted-map)]
      (if-let [m (.exec re s)]
        (recur (assoc res (.-index m) (first m)))
        res))))

(defn map-values
  "Map a function over the values of a collection of pairs (vector of vectors,
  hash-map, etc.) Optionally accept a result collection to put values into."
  ([m f rescoll]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([m f]
   (map-values m f {})))

(defn validate
  "Validate takes a map, and a map of validator/error message pairs, and returns a map of errors."
  [data validation]
  (->> validation
       (map (fn [[k [func message]]]
              (when (not (func (k data))) [k message])))
       (into (hash-map))))

(defn url-domain [url]
  "Gets the example.com part of a url"
  (let [domain
        (-> url
            (split "//")
            second
            (split "/")
            first
            (split ".")
            (->>
             (take-last 2)
             (join ".")))]
    (if (string? domain)
      domain url)))

(def nbsp (unescapeEntities "&nbsp;"))
