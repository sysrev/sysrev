(ns sysrev.util
  (:require
   [clojure.string :as str]
   [clojure.test.check.generators :as gen]
   [re-frame.core :as re-frame]
   [goog.string :refer [unescapeEntities]]
   [cljs-time.core :as t]
   [cljs-time.coerce :as tc]
   [cljs-time.format :as tformat]
   [cljsjs.jquery]))

(defn integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed 
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (and (re-matches #"^\d+$" (name k))
                                  (js/parseInt (name k)))
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn uuidify-map-keys
  "Maps parsed from JSON with UUID keys will have the UUID string values changed
  to keywords. This converts any UUID keywords to string values, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-uuid
                       (and (keyword? k)
                            (re-matches
                             #"^[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+$"
                             (name k))
                            (name k))
                       k-new (if (string? k-uuid) k-uuid k)
                       ;; uuidify sub-maps recursively
                       v-new (if (map? v)
                               (uuidify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn scroll-top []
  (. js/window (scrollTo 0 0)))

(defn schedule-scroll-top []
  (scroll-top))

(defn viewport-width []
  (-> (js/$ js/window) (.width)))

(defn mobile? []
  (< (viewport-width) 768))

(defn full-size? []
  (>= (viewport-width) 900))

(defn desktop-size? []
  (>= (viewport-width) 1100))

(def nbsp (unescapeEntities "&nbsp;"))

(defn number-to-word [n]
  (->> n (nth ["zero" "one" "two" "three" "four" "five" "six"
               "seven" "eight" "nine" "ten" "eleven" "twelve"
               "thirteen" "fourteen" "fifteen" "sixteen"])))

(defn dissoc-in [m ks]
  (assert (sequential? ks) "dissoc-in: invalid ks")
  (if (= 1 (count ks))
    (dissoc m (last ks))
    (update-in m (butlast ks) #(dissoc % (last ks)))))

(def date-from-string tformat/parse)

(defn today-string []
  (let [now (t/now)
        fmt (tformat/formatters :basic-date)]
    (tformat/unparse fmt now)))

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
         char-gen (gen/fmap char (gen/one-of [(gen/choose 65 90)
                                              (gen/choose 97 122)]))]
     (apply str (gen/sample char-gen length))))
  ([] (random-id 6)))

(defn url-domain [url]
  "Gets the example.com part of a url"
  (let [domain
        (-> url
            (str/split "//")
            second
            (str/split "/")
            first
            (str/split ".")
            (->>
             (take-last 2)
             (str/join ".")))]
    (if (string? domain)
      domain url)))

(defn validate
  "Validate takes a map, and a map of validator/error message pairs, and
  returns a map of errors."
  [data validation]
  (->> validation
       (map (fn [[k [func message]]]
              (when (not (func (k data))) [k message])))
       (into (hash-map))))

(defn wrap-prevent-default
  "Wraps an event handler function to prevent execution of a default
  event handler. Tested for on-submit event."
  [f]
  (fn [event]
    (f event)
    (when (.-preventDefault event)
      (.preventDefault event))
    #_ (set! (.-returnValue event) false)
    false))

(defn time-from-epoch [epoch]
  (tc/from-long (* epoch 1000)))

(defn time-elapsed-string [dt]
  (let [;; formatter (tf/formatter "yyyy-MM-dd")
        ;; s (tf/unparse formatter dt)
        now (t/now)
        intv (if (t/after? now dt)
               (t/interval dt now)
               (t/interval now now))
        minutes (t/in-minutes intv)
        hours (t/in-hours intv)
        days (t/in-days intv)
        weeks (t/in-weeks intv)
        months (t/in-months intv)
        years (t/in-years intv)
        pluralize (fn [n unit]
                    (str n " " (if (= n 1) (str unit) (str unit "s"))
                         " ago"))]
    (cond
      (>= years 1)   (pluralize years "year")
      (>= months 1)  (pluralize months "month")
      (>= weeks 1)   (pluralize weeks "week")
      (>= days 1)    (pluralize days "day")
      (>= hours 1)   (pluralize hours "hour")
      :else          (pluralize minutes "minute"))))

(defn go-back []
  (-> js/window .-history (.back)))

(defn get-dom-elt [selector]
  (-> (js/$ selector) (.get 0)))

(defn dom-elt-visible? [selector]
  (when-let [el (get-dom-elt selector)]
    (let [width  (or (-> js/window .-innerWidth)
                     (-> js/document .-documentElement .-clientWidth))
          height (or (-> js/window .-innerHeight)
                     (-> js/document .-documentElement .-clientHeight))
          rect (-> el (.getBoundingClientRect))]
      (and (>= (.-top rect) 0)
           (>= (.-left rect) 0)
           (<= (.-bottom rect) height)
           (<= (.-right rect) width)))))

(defn scroll-to-dom-elt [selector]
  (when-let [el (get-dom-elt selector)]
    (-> el (.scrollIntoView true))))

(defn ensure-dom-elt-visible
  "Scrolls to the position of DOM element if it is not currently visible."
  [selector]
  (when (false? (dom-elt-visible? selector))
    (scroll-to-dom-elt selector)
    (-> js/window (.scrollBy 0 -10))))

(defn ensure-dom-elt-visible-soon
  "Runs `ensure-dom-elt-visible` using js/setTimeout at multiple delay times."
  [selector]
  (doseq [ms [10 25 50 100]]
    (js/setTimeout #(ensure-dom-elt-visible selector) ms)))

(defn continuous-update-until
  "Call f continuously every n milliseconds until pred is satisified. pred
  must be a fn. on-success (unless nil) will be called one time after
  pred is satisified."
  [f pred on-success n]
  (js/setTimeout #(if (pred)
                    (when on-success (on-success))
                    (do (f)
                        (continuous-update-until f pred on-success n)))
                 n))
