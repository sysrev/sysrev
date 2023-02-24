(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'insilica/sysrev)
(def version
  (str "1.0."
       (b/git-count-revs nil)
       (when (not= "production" (b/git-process {:git-args "branch --show-current"}))
         (str "-" (subs (b/git-process {:git-args "rev-parse HEAD"}) 0 9)
              "-SNAPSHOT"))))

;; Most fns should return the opts map so they can be easily threeaded

(defn build-cljs [opts]
  (b/process {:command-args ["npx" "shadow-cljs" "release" "prod"]
              :dir "client"})
  opts)

(defn build-css [opts]
  (b/process {:command-args ["scripts/build-all-css"]})
  opts)

(defn find-invalid-tests [opts]
  (let [{:keys [exit]}
        #__ (b/process {:command-args ["clj" "-X:test" "sysrev.test.core/find-invalid-tests-cli!"]})]
    (when-not (zero? exit)
      (System/exit 1)))
  opts)

(defn run-tests [{:keys [aliases fail-fast? focus focus-meta randomize? watch?] :as opts}]
  (let [extra-config (cond-> {}
                       fail-fast? (assoc :kaocha/fail-fast? true)
                       focus (assoc :kaocha.filter/focus [focus])
                       focus-meta (assoc :kaocha.filter/focus-meta [focus-meta])
                       randomize? (assoc :kaocha.plugin.randomize/randomize? randomize?)
                       watch? (assoc :kaocha/watch? true))
        {:keys [exit]}
        #__ (b/process {:command-args ["clj"
                                       (str "-X" (str/join "" (or aliases [:test])))
                                       "sysrev.test.core/run-tests-cli!"
                                       ":extra-config" (pr-str extra-config)]})]
    (when-not (zero? exit)
      (System/exit 1)))
  opts)

(defn run-tests-staging [opts]
  (run-tests (assoc opts
                    :aliases [:test-staging :test]
                    :focus-meta :remote)))

(defn run-tests-prod [opts]
  (run-tests (assoc opts
                    :aliases [:test-prod :test]
                    :focus-meta :remote)))
