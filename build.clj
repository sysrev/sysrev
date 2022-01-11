(ns build
  (:require
   [clojure.data.xml :as dxml]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [org.corfield.build :as bb]
   [sysrev.file-util.interface :as file-util]
   [sysrev.junit.interface :as junit]))

(def lib 'insilica/sysrev)
(def version
  (str "1.0."
       (b/git-count-revs nil)
       (when (not= "production" (b/git-process {:git-args "branch --show-current"}))
         (str "-" (subs (b/git-process {:git-args "rev-parse HEAD"}) 0 9)
              "-SNAPSHOT"))))

;; Most fns should return the opts map so they can be easily threeaded

(defn create-target-dir [opts]
  (file-util/create-directories! (file-util/get-path "target"))
  opts)

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

(defn find-orphaned-tests [opts]
  (println "Checking test suite assignments")
  (let [{:keys [exit out]}
        #__ (b/process {:command-args ["clj" "-X:test" "sysrev.test.core/find-orphaned-tests-cli!"]
                        :out :capture})]
    (when-not (zero? exit)
      (println "Found orphans, tests not in a test suite:")
      (println out)
      (System/exit 1)))
  opts)

(defn run-test-suite-serial [opts focus-meta & [junit-file]]
  (let [run (fn [junit-file]
              (let [kaocha-config {:kaocha/fail-fast? true
                                   :kaocha.filter/focus-meta [focus-meta]
                                   :kaocha.plugin.junit-dxml/target-file junit-file}
                    {:keys [exit] :as result}
                    #__ (b/process {:command-args ["bin/kaocha"
                                                   "--fail-fast"
                                                   "--focus-meta" (str focus-meta)
                                                   "--junit-xml-file" (str junit-file)]})]
                (when-not (zero? exit)
                  (throw (ex-info "Tests failed" {:result result})))))]
    (if junit-file
      (run junit-file)
      (file-util/with-temp-file [junit-file
                                 {:prefix "junit-"
                                  :suffix ".xml"}]
        (run junit-file))))
  opts)

(defn parse-junit-xml [input-stream]
  (try
    (dxml/parse input-stream)
    (catch javax.xml.stream.XMLStreamException e
      ;; Ignore empty files, e.g., for test suites that haven't been run yet
      (let [loc (.getLocation e)]
        (when-not (and (= 1 (.getColumnNumber loc))
                       (= 1 (.getLineNumber loc))
                       (str/includes? (.getMessage e) "Premature end of file"))
          (throw e))))))

(defn merge-junit-files [out-file in-files]
  (let [in-streams (map #(java.io.FileInputStream. (str %)) in-files)]
    (try
      (let [junit (->> in-streams
                       (map parse-junit-xml)
                       (apply junit/merge-xml))]
        (with-open [out-file (java.io.FileWriter. (str out-file))]
          (dxml/emit junit out-file)))
      (finally
        (doseq [is in-streams] (.close is))))))

(defn run-tests-serial [opts]
  (find-orphaned-tests opts)
  (create-target-dir opts)
  (let [suites [:unit :integration :e2e]]
    (file-util/with-temp-files [junit-files
                                {:num-files (count suites)
                                 :prefix "junit-"
                                 :suffix ".xml"}]
      (try
        (doseq [[suite junit-file] (map vector suites junit-files)]
          (println "Running" (name suite) "tests...")
          (run-test-suite-serial opts suite junit-file))
        (merge-junit-files "target/junit.xml" junit-files)
        (catch clojure.lang.ExceptionInfo e
          (merge-junit-files "target/junit.xml" junit-files)
          (throw e)))))
  opts)

(defn run-unit-tests [opts]
  (println "Running unit tests...")
  (run-tests-serial opts :unit))

(defn run-integration-tests [opts]
  (println "Running integration tests...")
  (run-tests-serial opts :integration))

(defn run-e2e-tests [opts]
  (println "Running end-to-end tests...")
  (let [subsets 2]
    (file-util/with-temp-files [junit-files
                                {:num-files subsets
                                 :prefix "junit-"
                                 :suffix ".xml"}]
      (let [kaocha-config {:kaocha/fail-fast? true
                           :kaocha.filter/focus-meta [:e2e]}
            futs (map (fn [i]
                        (future
                          (-> (b/process {:command-args ["clj" "-X:test" "sysrev.test.core/run-test-subset!"
                                                         ":extra-config" (pr-str (assoc kaocha-config :kaocha.plugin.junit-dxml/target-file (str (nth junit-files i))))
                                                         ":index" (str i)
                                                         ":total-subsets" (str subsets)]
                                          :err :capture
                                          :out :capture})
                              (assoc :junit-file (nth junit-files i)))))
                      (range subsets))]
        (loop [[fut & more] (await-first-result futs 300)]
          (if (zero? (:exit @fut))
            (when more (recur (await-first-result more 300)))
            (let [{:keys [err junit-file out] :as result} @fut]
              (doseq [ft futs]
                (future-cancel ft))
              (println err)
              (println out)
              (file-util/copy! junit-file (file-util/get-path "target/junit.xml") #{:replace-existing})
              (throw (ex-info "Tests failed" {:result result}))))))))
  opts)
