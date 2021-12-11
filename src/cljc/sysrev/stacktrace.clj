(ns sysrev.stacktrace
  "Customized stacktrace output"
  (:require [clojure.string :as str]
            [clojure.stacktrace :as stack]
            [clojure.test :refer [*stack-trace-depth* *testing-contexts*
                                  inc-report-counter report testing-contexts-str
                                  testing-vars-str with-test-out]]
            [clojure.test.junit :as junit]))

(defn class-name [^StackTraceElement el]
  (.getClassName el))

(defn- boring-element?
  "Check if stacktrace element should be filtered out when printing."
  [el]
  (some #(str/starts-with? (class-name el) %)
        ["clojure.test" "clojure.lang" "clojure.main" "leiningen"
         "org.postgresql.jdbc" "org.postgresql.core" "clojure.java.jdbc"
         "com.zaxxer.hikari" "ring.middleware"]))

(defn- drop-trailing-stack-elements
  "Drop all stacktrace elements below entry point to project code."
  [elements]
  (->> (reverse elements)
       (drop-while #(not (str/includes? (class-name %) "sysrev")))
       (reverse)))

(defn filter-stacktrace?
  "Check whether stacktrace element filtering should be used."
  [elements]
  (some #(str/includes? (class-name %) "sysrev") elements))

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
     (let [elements (rest st)
           filter? (filter-stacktrace? elements)]
       (doseq [e (cond->> elements
                   filter?  (filter-stacktrace-elements)
                   n        (take n))]
         (print "    ")
         (stack/print-trace-element e)
         (newline))))))

;; Replacement for clojure.stacktrace/print-cause-trace
(defn print-cause-trace-custom
  ([tr] (print-cause-trace-custom tr nil))
  ([^Throwable tr n]
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
