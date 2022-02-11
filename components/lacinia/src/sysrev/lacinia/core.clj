(ns sysrev.lacinia.core
  (:require
   [com.walmartlabs.lacinia.constants :as constants]
   [com.walmartlabs.lacinia.executor :as executor]
   [com.walmartlabs.lacinia.selection :as selection]
   [medley.core :as medley])
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

(def scalars
  {:DateTime
   {:parse #'parse-DateTime
    :serialize #'serialize-DateTime}
   :Upload
   {:parse identity
    :serialize (constantly nil)}})

(defn ^Long parse-int-id [^String id]
  (case (first id)
    (\1 \2 \3 \4 \5 \6 \7 \8 \9) (parse-long id)
    nil))

(defn resolve-value [_ _ value]
  value)

(defn current-selection-names [context]
  (-> context
      executor/selection
      (or (get-in context [constants/parsed-query-key :selections 0]))
      selection/selections
      (->> (map (comp selection/field-name selection/field)))
      set))

(defn denamespace-keys [map-or-seq]
  (cond (map? map-or-seq) (medley/map-keys (comp keyword name) map-or-seq)
        (sequential? map-or-seq) (map denamespace-keys map-or-seq)))

(defn remap-keys [key-f map-or-seq]
  (cond (map? map-or-seq) (->> map-or-seq
                               denamespace-keys
                               (medley/map-keys key-f))
        (sequential? map-or-seq) (map (partial remap-keys key-f) map-or-seq)))
