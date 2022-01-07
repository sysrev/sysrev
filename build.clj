(ns build
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

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

(defn await-first-result [ids sleep-ms]
  (when (seq ids)
    (loop [[id & more] ids]
      (if (realized? id)
        [id (remove (partial = id) ids)]
        (if more
          (recur more)
          (do (Thread/sleep sleep-ms)
              (recur ids)))))))

(defn run-e2e-tests [opts]
  (let [subsets 2
        futs (map (fn [i]
                  (future
                    (b/process {:command-args ["clj" "-X:test" "sysrev.test.core/run-test-subset!" ":index" (str i) ":total-subsets" (str subsets) ":extra-config" "{:kaocha/fail-fast? true :kaocha.filter/focus-meta [:e2e]}"]
                                :err :capture
                                :out :capture})))
                (range subsets))]
    (loop [[fut & more] (await-first-result futs 300)]
      (if (zero? (:exit @fut))
        (when more (recur (await-first-result more 300)))
        (do (doseq [ft futs]
              (future-cancel ft))
            (throw (ex-info "Tests failed" {:result @fut}))))))
  opts)
