(ns sysrev.lacinia.core
  (:import
   (java.sql Timestamp)
   (java.time Instant)
   (java.time.format DateTimeFormatter)))

(defn parse-DateTime [x]
  (-> (try
        (when (string? x)
          (-> (.parse DateTimeFormatter/ISO_INSTANT x)
              Instant/from))
        (catch Exception _))
      (or
       (throw
        (ex-info "Must be a string representing a DateTime in ISO-8061 instant format, such as \"2011-12-03T10:15:30Z\"."
                 {:value x})))))

(defn serialize-DateTime [datetime]
  (cond
    (instance? Instant datetime)
    (.format DateTimeFormatter/ISO_INSTANT datetime)

    (instance? Timestamp datetime)
    (serialize-DateTime (.toInstant ^Timestamp datetime))))

(defn parse-NonNegativeInt [x]
  (if (nat-int? x)
    x
    (throw (ex-info "Must be a non-negative integer."
                    {:value x}))))

(defn parse-PositiveInt [x]
  (if (pos-int? x)
    x
    (throw (ex-info "Must be a positive integer."
                    {:value x}))))

(def scalars
  {:DateTime
   {:parse #'parse-DateTime
    :serialize #'serialize-DateTime}
   :NonNegativeInt
   {:parse #'parse-NonNegativeInt
    :serialize identity}
   :PositiveInt
   {:parse #'parse-PositiveInt
    :serialize identity}
   :Upload
   {:parse identity
    :serialize (constantly nil)}})

(defn resolve-value [_ _ value]
  value)
