(ns sysrev.util
  (:require [clojure.string :as str]
            [goog.string :refer [unescapeEntities]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as tformat]
            [cljsjs.jquery]
            [cljsjs.moment]
            [sysrev.shared.util :refer [parse-integer ensure-pred]])
  (:require-macros [reagent.interop :refer [$]]))

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
                                  (parse-integer (name k)))
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

(defn viewport-height []
  (-> (js/$ js/window) (.height)))

(defn mobile? []
  (< (viewport-width) 768))

(defn full-size? []
  (>= (viewport-width) 992))

(defn desktop-size? []
  (>= (viewport-width) 1160))

(defn annotator-size? []
  (>= (viewport-width) 1100))

(defn get-layout-status []
  [(mobile?) (full-size?) (desktop-size?)])

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

(defn today-string []
  (tformat/unparse (tformat/formatters :basic-date) (t/now)))

(defn is-today? [utc-date]
  (let [today (t/today)
        date (t/to-default-time-zone utc-date)]
    (and
     (= (t/day today) (t/day date))
     (= (t/month today) (t/month date))
     (= (t/year today) (t/year date)))))

(defn url-domain [url]
  "Gets the example.com part of a url"
  (let [sp str/split]
    (or (some-> url (sp "//") second (sp "/") first (sp ".")
                (some->> (take-last 2)
                         (str/join ".")
                         (ensure-pred string?)))
        url)))

(defn validate
  "Validate takes a map, and a map of validator/error message pairs, and
  returns a map of errors."
  [data validation]
  (->> validation
       (map (fn [[k [func message]]]
              (when (not (func (k data))) [k message])))
       (into (hash-map))))

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

(defn event-input-value
  "Returns event.target.value from a DOM event."
  [event]
  (-> event ($ :target) ($ :value)))

(defn vector->hash-map
  "Convert a vector into a hash-map with keys that correspond to the val of kw in each element"
  [v kw]
  (->> v
       (map #(hash-map (kw %) %))
       (apply merge)))

(defn input-focused? []
  (let [el js/document.activeElement]
    (when (and el (or (-> (js/$ el) (.is "input"))
                      (-> (js/$ el) (.is "textarea"))))
      el)))

;; https://stackoverflow.com/questions/3169786/clear-text-selection-with-javascript
(defn clear-text-selection
  "Clears any user text selection in window."
  []
  ;; don't run if input element is focused, will interfere with focus/behavior
  (when-not (input-focused?)
    (cond
      (-> js/window .-getSelection)
      (do (cond (-> js/window (.getSelection) .-empty) ;; Chrome
                (-> js/window (.getSelection) (.empty))

                (-> js/window (.getSelection) .-removeAllRanges) ;; Firefox
                (-> js/window (.getSelection) (.removeAllRanges))))

      (-> js/document .-selection) ;; IE?
      (-> js/document .-selection (.empty)))))

(defn clear-text-selection-soon
  "Runs clear-text-selection after short js/setTimeout delays."
  []
  (doseq [ms [5 25 50]]
    (js/setTimeout #(clear-text-selection) ms)))

(defn wrap-prevent-default
  "Wraps an event handler function to prevent execution of a default
  event handler. Tested for on-submit event."
  [f]
  (when f
    (fn [event]
      (f event)
      (when (.-preventDefault event)
        (.preventDefault event))
      #_ (set! (.-returnValue event) false)
      false)))

(defn wrap-stop-propagation
  [f]
  (when f
    (fn [event]
      (when (.-stopPropagation event)
        ($ event stopPropagation))
      (f event))))

(defn wrap-user-event
  "Wraps an event handler for an event triggered by a user click.

  Should be used for all such events (e.g. onClick, onSubmit).

  Handles issue of unintentional text selection on touchscreen devices.

  {
    f :
      Base event handler function; `(fn [event] ...)`
      `wrap-user-event` will return nil when given nil value for `f`.
    timeout :
      Default false. When true, runs inner handler via `js/setTimeout`.
      This breaks (at least) ability to access `(.-target event)`.
    prevent-default :
      Adds wrap-prevent-default at outermost level of handler.
    stop-propagation :
      Adds `(.stopPropagation event)` before inner handler executes.
    clear-text-after :
      When true (default), runs `(clear-text-selection-soon)` after
      inner handler executes.
    clear-text-before :
      When true, runs `(clear-text-selection)` before inner handler executes.
      Defaults to true when `timeout` is set to false.
  }"
  [f & {:keys [timeout prevent-default stop-propagation
               clear-text-after clear-text-before]
        :or {timeout false
             prevent-default false
             stop-propagation false
             clear-text-after true
             clear-text-before nil}}]
  (when f
    (let [clear-text-before (if (boolean? clear-text-before)
                              clear-text-before
                              (not timeout))
          wrap-handler
          (fn [event]
            (when clear-text-before
              (clear-text-selection))
            (let [result (f event)]
              (when clear-text-after
                (clear-text-selection-soon))
              result))]
      (cond->
          (fn [event]
            ;; Add short delay before processing event to allow touchscreen
            ;; events to resolve.
            (if timeout
              (do (js/setTimeout #(wrap-handler event) 20)
                  #_ (js/setTimeout #(f event) 20)
                  true)
              (wrap-handler event))
            true)
        stop-propagation (wrap-stop-propagation)
        prevent-default (wrap-prevent-default)))))

(defn on-event-value
  "Convenience function for processing input values from events. Takes a
  function which receives event input value and performs some side
  effect; returns a DOM event handler function (for :on-change etc)."
  [handler]
  (wrap-prevent-default #(-> % event-input-value (handler))))

(defn no-submit
  "Returns on-submit handler to block default action on forms."
  []
  (wrap-prevent-default (fn [_] nil)))

;; https://www.kirupa.com/html5/get_element_position_using_javascript.htm
(defn get-element-position [el]
  (letfn [(get-position [el x y]
            (let [next-el ($ el :offsetParent)
                  [next-x next-y]
                  (if (= ($ el :tagName) "BODY")
                    (let [xscroll (if (number? ($ el :scrollLeft))
                                    ($ el :scrollLeft)
                                    (-> ($ js/document :documentElement)
                                        ($ :scrollLeft)))
                          yscroll (if (number? ($ el :scrollTop))
                                    ($ el :scrollTop)
                                    (-> ($ js/document :documentElement)
                                        ($ :scrollTop)))]
                      [(+ x
                          ($ el :offsetLeft)
                          (- xscroll)
                          ($ el :clientLeft))
                       (+ y
                          ($ el :offsetTop)
                          (- yscroll)
                          ($ el :clientTop))])
                    [(+ x
                        ($ el :offsetLeft)
                        (- ($ el :scrollLeft))
                        ($ el :clientLeft))
                     (+ y
                        ($ el :offsetTop)
                        (- ($ el :scrollTop))
                        ($ el :clientTop))])]
              (if (or (nil? next-el) (undefined? next-el))
                {:top next-y :left next-x}
                (get-position next-el next-x next-y))))]
    (when-not (or (nil? el) (undefined? el))
      (get-position el 0 0))))

(defn get-scroll-position []
  {:top  (or ($ js/window :pageYOffset)
             (-> ($ js/document :documentElement) ($ :scrollTop)))
   :left (or ($ js/window :pageXOffset)
             (-> ($ js/document :documentElement) ($ :scrollLeft)))})

(defn get-url-path []
  (str js/window.location.pathname
       js/window.location.search
       js/window.location.hash))

(defn write-json [x]
  (js/JSON.stringify (clj->js x)))

(defn read-json [s]
  (js->clj (js/JSON.parse s) :keywordize-keys true))

(defn parse-css-px [px-str]
  (parse-integer (second (re-matches #"(\d+)px" px-str))))

(defn update-sidebar-height []
  (when (pos? (-> (js/$ ".column.panel-side-column") .-length))
    (let [total-height (viewport-height)
          header-height (or (some-> (js/$ "div.menu.site-menu") (.height)) 0)
          footer-height (or (some-> (js/$ "div#footer") (.height)) 0)
          body-font (or (some-> (js/$ "body") (.css "font-size") parse-css-px) 14)
          max-height-px (- total-height
                           (+ header-height footer-height
                              ;; "Labels / Annotations" menu
                              (* 4 body-font)
                              ;; "Save / Skip" buttons
                              (* 5 body-font)
                              ;; Extra space
                              (* 4 body-font)))
          label-css ".panel-side-column .ui.segments.label-editor-view"
          annotate-css ".ui.segments.annotation-menu.abstract"
          label-font (or (some-> (js/$ label-css) (.css "font-size") parse-css-px)
                         body-font)
          annotate-font (or (some-> (js/$ annotate-css) (.css "font-size") parse-css-px)
                            body-font)
          label-height-em (/ (* 1.0 max-height-px) label-font)
          annotate-height-em (/ (* 1.0 max-height-px) annotate-font)]
      (-> (js/$ label-css)    (.css "max-height" (str label-height-em "em")))
      (-> (js/$ annotate-css) (.css "max-height" (str annotate-height-em "em"))))))

(defn unix-epoch->date-string [unix]
  (-> unix (js/moment.unix) ($ format "YYYY-MM-DD HH:mm:ss")))
