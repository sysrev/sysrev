(ns sysrev.json.interface-test
  (:require
   [clojure.test :refer :all]
   [sysrev.json.interface :as json]))

(def re-eof #"JSON error \(end\-of\-file")

(deftest ^:unit test-read-str
  (is (= {} (json/read-str "{}")))
  (is (= {} (json/read-str "{} \n")))
  (is (= {"a" {"1" "c" "d " "x"}}
         (json/read-str "{\"a\":{\"1\":\"c\",\"d \":\"x\"}}")))
  (are [s] (thrown-with-msg? Exception re-eof (json/read-str s))
    ""
    " \n\t  "
    ""
    "{"
    "[")
  (is (thrown-with-msg? Exception
                        #"JSON error \(invalid number"
                        (json/read-str "1a")))
  (is (thrown-with-msg? Exception
                        #"JSON error \(invalid number"
                        (json/read-str "1.")))
  (is (thrown-with-msg? Exception
                        #"JSON error \(found extra"
                        (json/read-str "{} []")))
  (is (thrown-with-msg? Exception
                        #"JSON error \(unexpected character\)"
                        (json/read-str (String. (byte-array [0]))))))
