(ns sysrev.test.import.pubmed
  (:require [clojure.test :refer :all]
            [sysrev.import.pubmed :refer [fetch-pmid-xml parse-pmid-xml]]
            [sysrev.util :refer [parse-xml-str xml-find]]
            [clojure.string :as str]))

(def ss (partial clojure.string/join "\n"))

(def xml-vector-node
  (ss ["<doc>"
         "<test>"
           "<A>1</A>"
           "<A>2</A>"
           "<A>3</A>"
         "</test>"
       "</doc>"]))


(deftest find-xml-test
  (let [pxml (parse-xml-str xml-vector-node)
        els (xml-find pxml [:test :A])]
    (is (= (count els) 3))))

(deftest parse-pmid-test
  (let [xml (fetch-pmid-xml 11592337)
        parsed (parse-pmid-xml xml)]
    (is (str/starts-with? (:abstract parsed) "OBJECTIVE: To determine"))
    (is (str/includes? (:abstract parsed) "\n\nSAMPLE POPULATION: Corneal"))
    (is (= (str/join "; " (:authors parsed)) "Hendrix, DV.; Ward, DA.; Barnhill, MA."))))