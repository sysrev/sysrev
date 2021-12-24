(ns sysrev.etaoin-test.interface
  "Helper fns and macros for writing etaoin-based tests."
  (:require
   [sysrev.etaoin-test.core :as core]))

(defn clear
  "Clears one or more input elements.

  Uses the Ctrl-Home, Ctrl-Shift-End, Delete key sequence to clear the
  input in order to ensure that event handlers are fired. Use
  `etaoin.api/clear` if you need to clear an element without keypresses and
  don't care about event handlers."
  [driver q & more-qs]
  (apply core/clear driver q more-qs))

(defn click
  "Clicks on an element.

  Automatically retries when a stale element reference exception is thrown."
  [driver q]
  (core/click driver q))

(defn click-visible
  "Waits until an element becomes visible, then clicks on it.

  Automatically retries when a stale element reference exception is thrown."
  [driver q & opt]
  (core/click-visible driver q opt))

(defmacro is-click-visible
  "Asserts that `click-visible` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q & [opt]]
  `(core/is-click-visible ~driver ~q ~opt))

(defmacro is-exists?
  "Asserts that `etaoin.api/exists?` returns true."
  [driver q & more]
  `(core/is-exists? ~driver ~q ~@more))

(defmacro is-visible?
  "Asserts that `etaoin.api/visible?` returns true."
  [driver q & more]
  `(core/is-visible? ~driver ~q ~@more))

(defmacro is-not-exists?
  "Asserts that `etaoin.api/exists?` returns false."
  [driver q & more]
  `(core/is-not-exists? ~driver ~q ~@more))

(defmacro is-not-pred
  "Asserts that pred, when called with driver as the first argument, returns
  false. Catches etaoin timeout exceptions and causes a test pass instead.

  Usage:
  (doto driver
    (is-not-pred etaoin.api/exists? :id)
    (is-not-pred etaoin.api/exists? :id2))"
  [driver pred & args]
  `(core/is-pred ~driver ~pred ~@args))

(defmacro is-not-visible?
  "Asserts that `etaoin.api/visible?` returns false."
  [driver q & more]
  `(core/is-not-visible? ~driver ~q ~@more))

(defmacro is-pred
  "Asserts that pred, when called with driver as the first argument, returns
  true.

  Catches etaoin timeout exceptions and causes a test failure instead.

  Usage:
  (doto driver
    (is-pred etaoin.api/exists? :id)
    (is-pred etaoin.api/exists? :id2))"
  [driver pred & args]
  `(core/is-pred ~driver ~pred ~@args))

(defmacro is-wait-exists
  "Asserts that `etaoin.api/wait-exists` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q & [opt]]
  `(core/is-wait-exists ~driver ~q ~opt))

(defmacro is-wait-visible
  "Asserts that `etaoin.api/wait-visible` succeeds.

  Catches etaoin timeout exceptions and causes a test failure instead."
  [driver q & [opt]]
  `(core/is-wait-visible ~driver ~q ~opt))
