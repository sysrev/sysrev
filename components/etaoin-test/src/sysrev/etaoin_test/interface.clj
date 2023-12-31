(ns sysrev.etaoin-test.interface
  "Helper fns and macros for writing etaoin-based tests."
  (:require
   [sysrev.etaoin-test.core :as core]))

(defn clear
  "Clears one or more input elements.

  Uses a sequence of keypresses to clear the
  input in order to ensure that event handlers are fired. Use
  `etaoin.api/clear` if you need to clear an element without keypresses and
  don't care about event handlers.

  Arguments:

  - `driver`: a etaoin driver instance
  - `q`: a query term (see `etaoin.api/query`)
  - `more-qs`: additional query terms, if clearing multiple inputs"
  [driver q & more-qs]
  (apply core/clear driver q more-qs))

(defn clear-visible
  "Waits for input elements to become visible, and then clears them with
  `clear`.

  Arguments:

  - `driver`: a etaoin driver instance
  - `q`: a query term (see `etaoin.api/query`)
  - `more-qs`: additional query terms, if clearing multiple inputs"
  [driver q & more-qs]
  (apply core/clear-visible driver q more-qs))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn click
  "Clicks on an element.

  Automatically retries when a stale element reference exception is thrown.

  Arguments:

  - `driver`: a etaoin driver instance
  - `q`: a query term (see `etaoin.api/query`)"
  [driver q]
  (core/click driver q))

(defn click-visible
  "Waits until an element becomes visible, then clicks on it.

  Automatically retries when a stale element reference exception is thrown.

  Arguments:

  - `driver`: a etaoin driver instance
  - `q`: a query term (see `etaoin.api/query`)
  - `opt`: a map of options (see `etaoin.api/wait-predicate`)"
  [driver q & opt]
  (core/click-visible driver q opt))

(defn fill-visible
  "Waits until an input becomes visible, then adds text to it.

  Automatically retries when a stale element reference exception is thrown.

  Arguments:

  - `driver`: a etaoin driver instance
  - `q`: a query term (see `etaoin.api/query`)
  - `text`: A String to add to the input element, or nil
  - `opt`: a map of options (see `etaoin.api/wait-predicate`)"
  [driver q text & [opt]]
  (core/fill-visible driver q text opt))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn has-class?
  "Checks whether an element has a specific class.

  Automatically retries when a stale element reference exception is thrown.

  Arguments:

  - `driver`: a etaoin driver instance
  - `q`: a query term (see `etaoin.api/query`)
  - `class` : a css class string"
  [driver q class]
  (core/has-class? driver q class))

(defmacro is-click-visible
  "Asserts that `click-visible` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q & [opt msg]]
  `(core/is-click-visible ~driver ~q ~opt ~msg))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro is-exists?
  "Asserts that `etaoin.api/exists?` returns true."
  [driver q & [msg]]
  `(core/is-exists? ~driver ~q ~msg))

(defmacro is-fill-visible
  "Asserts that `fill-visible` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q text & [opt msg]]
  `(core/is-fill-visible ~driver ~q ~text ~opt ~msg))

(defmacro is-not-exists?
  "Asserts that `etaoin.api/exists?` returns false."
  [driver q & [msg]]
  `(core/is-not-exists? ~driver ~q ~msg))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro is-not-pred
  "Asserts that pred, when called with driver as the first argument, returns
  false. Catches etaoin timeout exceptions and causes a test pass instead.

  Usage:
  (doto driver
    (is-not-pred nil etaoin.api/exists? :id)
    (is-not-pred nil etaoin.api/exists? :id2))"
  [driver msg pred & args]
  `(core/is-not-pred ~driver ~msg ~pred ~@args))

(defmacro is-not-visible?
  "Asserts that `etaoin.api/visible?` returns false."
  [driver q & [msg]]
  `(core/is-not-visible? ~driver ~q ~msg))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro is-pred
  "Asserts that pred, when called with driver as the first argument, returns
  true.

  Catches etaoin timeout exceptions and causes a test failure instead.

  Usage:
  (doto driver
    (is-pred nil etaoin.api/exists? :id)
    (is-pred nil etaoin.api/exists? :id2))"
  [driver msg pred & args]
  `(core/is-pred ~driver ~msg ~pred ~@args))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro is-visible?
  "Asserts that `etaoin.api/visible?` returns true."
  [driver q & [msg]]
  `(core/is-visible? ~driver ~q ~msg))

(defmacro is-wait-pred
  "Asserts that `(pred)` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [pred & [opt msg]]
  `(core/is-wait-pred ~pred ~opt ~msg))

(defmacro is-wait-exists
  "Asserts that `etaoin.api/wait-exists` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q & [opt msg]]
  `(core/is-wait-exists ~driver ~q ~opt ~msg))

(defmacro is-wait-visible
  "Asserts that `etaoin.api/wait-visible` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q & [opt msg]]
  `(core/is-wait-visible ~driver ~q ~opt ~msg))
