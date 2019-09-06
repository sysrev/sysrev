;;;
;;; Customized stacktrace output
;;;

(ns sysrev.stacktrace
  (:require [clojure.string :as str]
            [clojure.stacktrace :as stack]
            [clojure.test :refer :all]
            [clojure.test.junit :as junit]))

#_ (defonce ^:private default-print-stack-trace stack/print-stack-trace)

#_ (defn- sysrev-element? ^:unused [e] (str/includes? (.getClassName e) "sysrev"))

(defn- boring-element?
  "Check if stacktrace element should be filtered out when printing."
  [e]
  (some #(str/starts-with? (.getClassName e) %)
        ["clojure.test" "clojure.lang" "clojure.main" "leiningen"]))

(defn- drop-trailing-stack-elements
  "Drop all stacktrace elements below entry point to project code."
  [elements]
  (->> (reverse elements)
       (drop-while #(not (str/includes? (.getClassName %) "sysrev")))
       (reverse)))

(defn filter-stacktrace?
  "Check whether stacktrace element filtering should be used."
  [elements]
  #_ (some #(str/includes? (.getClassName %) "default_fixture") elements)
  (some #(str/includes? (.getClassName %) "sysrev") elements))

(defn filter-stacktrace-elements
  "Filter a sequence of stacktrace elements to remove unhelpful entries."
  [elements]
  ;; keep top 6 elements always for clarity
  (concat (take 6 elements)
          (->> (drop 6 elements)
               (drop-trailing-stack-elements)
               (remove boring-element?))))

;; Modified from clojure.stacktrace/print-stack-trace
(defn print-stack-trace-custom
  ([tr] (print-stack-trace-custom tr nil))
  ([^Throwable tr n]
   (let [st (.getStackTrace tr)]
     (stack/print-throwable tr)
     (newline)
     (print " at ")
     (if-let [e (first st)]
       (stack/print-trace-element e)
       (print "[empty stack trace]"))
     (newline)
     (let [elements (if (nil? n)
                      (rest st)
                      (take (dec n) (rest st)))
           filter? (filter-stacktrace? elements)]
       (doseq [e (cond-> elements filter? (filter-stacktrace-elements))]
         (print "    ")
         (stack/print-trace-element e)
         (newline))))))

;; Replacement for clojure.stacktrace/print-cause-trace
(defn print-cause-trace-custom
  ([tr] (print-cause-trace-custom tr nil))
  ([tr n]
   (print-stack-trace-custom tr n)
   (when-let [cause (.getCause tr)]
     (print "Caused by: " )
     (recur cause n))))

;; Replaces method in clojure.test to use print-cause-trace-custom
(defmethod report :error [m]
  (with-test-out
    (inc-report-counter :error)
    (println "\nERROR in" (testing-vars-str m))
    (when (seq *testing-contexts*) (println (testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (println "expected:" (pr-str (:expected m)))
    (print "  actual: ")
    (let [actual (:actual m)]
      (if (instance? Throwable actual)
        (print-cause-trace-custom actual *stack-trace-depth*)
        (prn actual)))))

;; Replacement for clojure.test.junit/error-el
(defn error-el-custom
  [message expected actual]
  (junit/message-el 'error
                    message
                    (pr-str expected)
                    (if (instance? Throwable actual)
                      (with-out-str (print-cause-trace-custom actual *stack-trace-depth*))
                      (prn actual))))

;; Replaces method in clojure.test.junit to use print-cause-trace-custom
(defmethod junit/junit-report :error [m]
  (with-test-out
    (inc-report-counter :error)
    (error-el-custom (:message m)
                     (:expected m)
                     (:actual m))))
