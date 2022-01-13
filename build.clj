(ns build
  (:require
   [clojure.data.xml :as dxml]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
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

(defn find-invalid-tests [opts]
  (let [{:keys [exit]}
        #__ (b/process {:command-args ["clj" "-X:test" "sysrev.test.core/find-invalid-tests-cli!"]})]
    (when-not (zero? exit)
      (System/exit 1)))
  opts)

(defn run-tests [opts]
  (let [{:keys [exit]}
        #__ (b/process {:command-args ["clj" "-X:test" "sysrev.test.core/run-tests-cli!"]})]
    (when-not (zero? exit)
      (System/exit 1)))
  opts)