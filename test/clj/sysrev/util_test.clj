(ns sysrev.util-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [sysrev.util :as util]))

(def xml-vector-node
  (str/join
   "\n"
   ["<doc>"
    "<test>"
    "<A>1</A>"
    "<A>2</A>"
    "<A>3</A>"
    "</test>"
    "</doc>"]))

(deftest ^:unit parse-xml
  (let [pxml (util/parse-xml-str xml-vector-node)
        els (util/xml-find pxml [:test :A])]
    (is (= (count els) 3))))

