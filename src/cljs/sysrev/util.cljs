(ns sysrev.util
  (:require [goog.string :as gstring :refer [unescapeEntities]]
            [goog.string.format]
            [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as tformat]
            [cljsjs.jquery]
            [cljsjs.moment]
            [reagent.interop :refer-macros [$]]
            [sysrev.shared.util :refer [parse-integer ensure-pred]]))

(defn scroll-top []
  (. js/window (scrollTo 0 0))
  nil)

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

(defn today-string []
  (tformat/unparse (tformat/formatters :basic-date) (t/now)))

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

(defn now-ms
  "Returns current time in epoch milliseconds."
  []
  (tc/to-long (t/now)))

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

(defonce ^:private run-after-condition-state (atom #{}))

(defn run-after-condition
  "Run function `on-ready` as soon as function `is-ready` returns
  logical true, re-checking every `interval` ms. `id` can be any value
  and should identify this call to `run-after-condition` in order to
  prevent multiple duplicate instances from running simultaneously.
  `is-abort` is optionally a function that will be checked along with
  `is-ready`, and if returns true this will be aborted without running
  `on-ready`."
  [id is-ready on-ready & {:keys [interval is-abort]
                           :or {interval 25
                                is-abort (constantly false)}}]
  ;; Check if a `run-after-condition` instance is already active
  ;; for this `id` value; if so, don't start a new one.
  (when-not (contains? @run-after-condition-state id)
    (swap! run-after-condition-state #(conj % id))
    (letfn [(run []
              (cond (is-ready)
                    ;; Finished; remove `id` from list of active `run-after-condition`
                    ;; instances and run final `on-ready` function.
                    (do (swap! run-after-condition-state
                               #(set (remove (partial = id) %)))
                        (on-ready))
                    ;; If `is-abort` function was provided and returns true,
                    ;; abort this loop and remove `id` from list.
                    (and is-abort (is-abort))
                    (swap! run-after-condition-state
                           #(set (remove (partial = id) %)))
                    ;; Condition not yet true; try again after `interval` ms.
                    :else (js/setTimeout run interval)))]
      ;; Begin loop
      (run))))

(defn event-input-value
  "Returns event.target.value from a DOM event."
  [event]
  (-> event ($ :target) ($ :value)))

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
          wrap-handler (fn [event]
                         (when clear-text-before (clear-text-selection))
                         (let [result (f event)]
                           (when clear-text-after (clear-text-selection-soon))
                           result))]
      (cond-> (fn [event]
                ;; Add short delay before processing event to allow touchscreen
                ;; events to resolve.
                (if timeout
                  (js/setTimeout #(wrap-handler event) 20)
                  (wrap-handler event))
                true)
        stop-propagation  (wrap-stop-propagation)
        prevent-default   (wrap-prevent-default)))))

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

(defn condensed-number
  "Condense numbers over 1000 to be factors of k"
  [i]
  (if (> i 999)
    (-> (/ i 1000) ($ toFixed 1) (str "K"))
    (str i)))

(defn ui-theme-from-dom-css []
  (cond (pos? (-> (js/$ "link[href='/css/style.dark.css']") ($ :length)))
        "Dark"
        (pos? (-> (js/$ "link[href='/css/style.default.css']") ($ :length)))
        "Default"))

(defn format
  "Wrapper to provide goog.string/format functionality from this namespace."
  [format-string & args]
  (apply goog.string/format format-string args))

(defn log
  "Wrapper to run js/console.log using printf-style formatting."
  [format-string & args]
  (js/console.log (apply format format-string args)))

(defn log-err
  "Wrapper to run js/console.error using printf-style formatting."
  [format-string & args]
  (js/console.error (apply format format-string args)))

(defn log-warn
  "Wrapper to run js/console.error using printf-style formatting."
  [format-string & args]
  (js/console.warn (apply format format-string args)))
