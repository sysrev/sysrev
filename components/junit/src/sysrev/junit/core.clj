(ns sysrev.junit.core
  "Operations on JUnit XML data.
  The best JUnit XML documentation is https://llg.cubic.org/docs/junit/"
  (:require
   [clojure.data.xml :as dxml]
   [clojure.spec.alpha :as s]
   [sysrev.junit.interface.spec :as spec])
  (:import
   (java.io FileInputStream FileWriter)))

(defn parse-double [s]
  (try
    (Double/parseDouble s)
    (catch Exception _)))

(defn parse-long [s]
  (try
    (Long/parseLong s)
    (catch Exception _)))

(defn merge-testsuite-xml [& ms]
  (if (< (count ms) 2)
    (first ms)
    (let [ms (sort-by (comp :timestamp :attrs) ms)
          {{:keys [name package timestamp]} :attrs} (last ms)
          concat-content (fn [tag]
                           (->> (mapcat :content ms)
                                (filter #(= tag (:tag %)))
                                (mapcat :content)))
          sum-attr (fn [f attr]
                     (->> (map #(f (or (some-> % :attrs attr) "0")) ms)
                          (reduce +)
                          str))]
      (apply
       dxml/element
       :testsuite
       {:errors (sum-attr parse-long :errors)
        :failures (sum-attr parse-long :failures)
        :name name
        :package package
        :skipped (sum-attr parse-long :skipped)
        :tests (sum-attr parse-long :tests)
        :time (sum-attr parse-double :time)
        :timestamp timestamp}
       (apply dxml/element :system-err {} (concat-content :system-err))
       (apply dxml/element :system-out {} (concat-content :system-out))
       (filter #(= :testcase (:tag %))
               (mapcat :content ms))))))

(s/fdef merge-testsuite-xml
  :args (s/cat :testsuites (s/* ::spec/testsuite))
  :ret (s/nilable ::spec/testsuite))

(defn merge-xml [& ms]
  (if (< (count ms) 2)
    (first ms)
    (let [suites (->> (mapcat :content ms)
                      (filter map?)
                      (group-by
                       (fn [{:keys [attrs]}] [(:package attrs) (:name attrs)])))]
      (apply
       dxml/element
       :testsuites
       {}
       (map #(apply merge-testsuite-xml %) (vals suites))))))

(s/fdef merge-xml
  :args (s/cat :testsuitess (s/* ::spec/testsuites))
  :ret (s/nilable ::spec/testsuites))

(defn merge-files! [out-file in-files]
  (let [in-streams (map #(FileInputStream. (str %)) in-files)]
    (try
      (let [junit (->> in-streams
                       (map dxml/parse)
                       (apply merge-xml))]
        (with-open [out-file (FileWriter. (str out-file))]
          (dxml/emit junit out-file)))
      (finally
        (doseq [is in-streams] (.close is)))))
  out-file)
