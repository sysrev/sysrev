(ns sysrev.etaoin-test.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [is]]
   [etaoin.api :as ea]
   [etaoin.keys :as keys]
   [sysrev.etaoin-test.interface.spec :as spec])
  (:import
   (clojure.lang ExceptionInfo)))

(defn retry-stale-element
  "Calls f, and retries it up to several times if a stale element reference
  exception is caught.

  The stale element reference exception can happen as a result of the DOM
  being updated."
  [f & [retries]]
  (try
    (f)
    (catch ExceptionInfo e
      (if (some->> e ex-data :response :value :message
                   (re-find #"^stale element reference.*"))
        (if (and retries (> retries 2))
          (throw (ex-info "Too many retries for stale element" {} e))
          (retry-stale-element f (inc (or retries 0))))
        (throw e)))))

(defn clear [driver q & more-qs]
  (doseq [query (cons q more-qs)]
    (ea/fill driver query (keys/with-ctrl keys/home (keys/with-shift keys/end)) keys/delete)))

(s/fdef clear
  :args (s/cat :driver ::spec/driver :queries (s/+ ::spec/query))
  :ret nil?)

(defn click [driver q]
  (retry-stale-element #(ea/click driver q))
  nil)

(s/fdef click
  :args (s/cat :driver ::spec/driver :q ::spec/query)
  :ret nil?)

(defn click-visible [driver q & [opt]]
  (retry-stale-element #(ea/click-visible driver q opt))
  nil)

(s/fdef click-visible
  :args (s/cat :driver ::spec/driver :q ::spec/query :opt (s/? (s/nilable map?)))
  :ret nil?)

(defn fill-visible [driver q text & [opt]]
  (retry-stale-element #(doto driver
                          (ea/wait-visible q opt)
                          (ea/fill q text)))
  nil)

(s/fdef fill-visible
  :args (s/cat :driver ::spec/driver
               :q ::spec/query
               :text (s/nilable string?)
               :opt (s/? (s/nilable map?)))
  :ret nil?)

(defmacro is-catch-timeout [form & [msg]]
  `(is
    (try
      ~form
      true
      (catch ExceptionInfo e#
        (if (-> e# ex-data :type (= :etaoin/timeout))
          false
          (throw e#))))
    ~msg))

(defmacro is-not-pred [driver msg pred & args]
  `(is-catch-timeout
    (not (~pred ~driver ~@args))
    ~msg))

(defmacro is-pred [driver msg pred & args]
  `(is-catch-timeout
    (~pred ~driver ~@args)
    ~msg))

(defmacro is-exists? [driver q & [msg]]
  `(is-pred ~driver ~msg ea/exists? ~q))

(defmacro is-not-exists? [driver q & [msg]]
  `(is-not-pred ~driver ~msg ea/exists? ~q))

(defmacro is-wait-exists [driver q & [opt msg]]
  `(is-catch-timeout
    (ea/wait-exists ~driver ~q ~opt)
    ~msg))

(defmacro is-visible? [driver q & [msg]]
  `(is-pred ~driver ~msg ea/visible? ~q))

(defmacro is-not-visible? [driver q & [msg]]
  `(is-not-pred ~driver ~msg ea/visible? ~q))

(defmacro is-wait-visible [driver q & [opt msg]]
  `(is-catch-timeout
    (ea/wait-visible ~driver ~q ~opt)
    ~msg))

(defmacro is-click-visible [driver q & [opt msg]]
  `(is-catch-timeout
    (click-visible ~driver ~q ~opt)
    ~msg))

(defmacro is-fill-visible [driver q & [opt msg]]
  `(is-catch-timeout
    (fill-visible ~driver ~q ~opt)
    ~msg))
