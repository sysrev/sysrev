(ns sysrev.etaoin-test.interface.spec
  (:require [clojure.spec.alpha :as s]))

(s/def ::args (s/nilable (s/coll-of string?)))
(s/def ::capabilities (s/nilable map?))
(s/def ::headless (s/nilable boolean?))
(s/def ::host string?)
(s/def ::locator (s/nilable string?))
(s/def ::port pos-int?)
(s/def ::type keyword?)
(s/def ::url string?)
(s/def ::driver
  (s/keys :req-un [::args ::capabilities ::headless ::host ::locator ::port ::type ::url]))
;; Example driver:
#_{:args ("chromedriver" "--port=40547"),
   :capabilities {:chromeOptions {:args ("--window-size=1600,1000" "--headless"),
                                  :binary "./chrome"},
                  :loggingPrefs {:browser "ALL"}},
   :env nil,
   :headless true,
   :host "127.0.0.1",
   :locator "xpath",
   :port 40547,
   :process nil ;#<java.lang.UNIXProcess@52169a9d>,
   :session "dac126ccff6de616e4e42295f6f59322",
   :type :chrome,
   :url "http://127.0.0.1:40547"}

;; query map entries
;; The :fn/* entries are implicitly checked by s/keys.
;; https://github.com/igrishaev/etaoin/blob/master/src/etaoin/xpath.clj

(s/def :fn/disabled string?)
(s/def :fn/enabled string?)
(s/def :fn/has-class string?)
(s/def :fn/has-classes string?)
(s/def :fn/has-string string?)
(s/def :fn/has-text string?)
(s/def :fn/link string?)
(s/def :fn/text string?)
(s/def ::css string?)
(s/def ::id string?)
(s/def ::index string?)
(s/def ::xpath string?)

(s/def ::query
  (s/or
   :id keyword?
   :xpath string?
   :compound (s/coll-of ::query)
   :map (s/keys :opt-un [::css ::id ::index ::xpath])))
