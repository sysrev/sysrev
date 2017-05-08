(ns sysrev.util
  (:require [clojure.test.check.generators :as gen]
            [pushy.core :as pushy]
            [sysrev.base :refer [history scroll-top]]
            [clojure.string :as str :refer [split join]]
            [goog.string :refer [unescapeEntities]]
            [cljs-time.core :as t]
            [cljs-time.format :as tformat]))

(defn nav
  "Change the current route."
  [route]
  (pushy/set-token! history route))

(defn nav-scroll-top
  "Change the current route then scroll to top of page."
  [route]
  (pushy/set-token! history route)
  (scroll-top))

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

(defn number-to-word [n]
  (->> n (nth ["zero" "one" "two" "three" "four" "five" "six"
               "seven" "eight" "nine" "ten" "eleven" "twelve"
               "thirteen" "fourteen" "fifteen" "sixteen"])))

(defn viewport-width []
  (-> (js/$ js/window) (.width)))

(defn mobile? []
  (< (viewport-width) 768))

(defn full-size? []
  (>= (viewport-width) 900))

(defn dissoc-in [m ks]
  (assert (sequential? ks) "dissoc-in: invalid ks")
  (if (= 1 (count ks))
    (dissoc m (last ks))
    (update-in m (butlast ks) #(dissoc % (last ks)))))

(defn short-uuid [uuid-str]
  (last (str/split uuid-str #"\-")))

(def date-from-string tformat/parse)

(defn is-today? [utc-date]
  (let [today (t/today)
        date (t/to-default-time-zone utc-date)]
    (and
     (= (t/day today) (t/day date))
     (= (t/month today) (t/month date))
     (= (t/year today) (t/year date)))))


(defn random-id
  "Generate a random-id to use for manually rendered components."
  ([len]
   (let [length (or len 6)
         char-gen (gen/fmap char (gen/one-of [(gen/choose 65 90) (gen/choose 97 122)]))]
     (apply str (gen/sample char-gen length))))
  ([] (random-id 6)))
