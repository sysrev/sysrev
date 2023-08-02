(ns sysrev.ris.interface-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sysrev.ris.interface :as ris]))

(defn load-reader [filename]
  (-> (str "sysrev/ris/test/" filename)
      io/resource
      io/reader))

(defn load-ris [filename]
  (-> (str "sysrev/ris/test/" filename)
      io/resource
      slurp))

(defn valid-reference? [x]
  (and (map? x) (:TY x)))

(defn compare-sequences [seq1 seq2]
  (let [zipped (map vector seq1 seq2)]
    (first (filter (fn [[a b]] (not= a b)) zipped))))

(deftest ^:unit test-reader->ris-maps
  (testing "Parsing a file with one reference"
    (is (= [{:VO ["9"], :JA ["Neural Computation"], :DO ["10.1162/neco.1997.9.8.1735"], :JO ["Neural Computation"], :SP ["1735"], :PY ["1997"], :IS ["8"], :AU ["S. Hochreiter" "J. Schmidhuber"], :Y1 ["15 Nov. 1997"], :T2 ["Neural Computation"], :TI ["Long Short-Term Memory"], :SN [""], :TY ["JOUR"], :AB ["Learning to store information over extended time intervals by recurrent backpropagation takes a very long time, mostly because of insufficient, decaying error backflow. We briefly review Hochreiter's (1991) analysis of this problem, then address it by introducing a novel, efficient, gradient based method called long short-term memory (LSTM). Truncating the gradient where this does not do harm, LSTM can learn to bridge minimal time lags in excess of 1000 discrete-time steps by enforcing constant error flow through constant error carousels within special units. Multiplicative gate units learn to open and close access to the constant error flow. LSTM is local in space and time; its computational complexity per time step and weight is O. 1. Our experiments with artificial data involve local, distributed, real-valued, and noisy pattern representations. In comparisons with real-time recurrent learning, back propagation through time, recurrent cascade correlation, Elman nets, and neural sequence chunking, LSTM leads to many more successful runs, and learns much faster. LSTM also solves complex, artificial long-time-lag tasks that have never been solved by previous recurrent network algorithms."], :EP ["1780"], :VL ["9"]}]
           (ris/reader->ris-maps (load-reader "one_article.ris")))))
  (testing "Parsing EndNote Online references"
    (let [ms (ris/reader->ris-maps (load-reader "endnoteonline.ris"))]
      (is (= 14 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing ERIC_ebsco_1232020_crl_assessment.txt"
    (let [ms (ris/reader->ris-maps (load-reader "ERIC_ebsco_1232020_crl_assessment.txt"))]
      (is (= 100 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing Google Scholar RefMan Export"
    (let [ms (ris/reader->ris-maps (load-reader "Google_Scholar_RefMan_export.ris"))]
      (is (= 3 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing IEEE Xplore Citation Download"
    (let [ms (ris/reader->ris-maps (load-reader "IEEE_Xplore_Citation_Download_2019.11.04.16.41.46.ris"))]
      (is (= 3 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing scopus.ris"
    (let [ms (ris/reader->ris-maps (load-reader "scopus.ris"))]
      (is (= 52 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing zotero.ris"
    (let [ms (ris/reader->ris-maps (load-reader "zotero.ris"))]
      (is (= 6 (count ms)))
      (is (every? valid-reference? ms)))))

(deftest ^:unit test-str->ris-maps
  (testing "Parsing a file with one reference"
    (is (= [{:VO ["9"], :JA ["Neural Computation"], :DO ["10.1162/neco.1997.9.8.1735"], :JO ["Neural Computation"], :SP ["1735"], :PY ["1997"], :IS ["8"], :AU ["S. Hochreiter" "J. Schmidhuber"], :Y1 ["15 Nov. 1997"], :T2 ["Neural Computation"], :TI ["Long Short-Term Memory"], :SN [""], :TY ["JOUR"], :AB ["Learning to store information over extended time intervals by recurrent backpropagation takes a very long time, mostly because of insufficient, decaying error backflow. We briefly review Hochreiter's (1991) analysis of this problem, then address it by introducing a novel, efficient, gradient based method called long short-term memory (LSTM). Truncating the gradient where this does not do harm, LSTM can learn to bridge minimal time lags in excess of 1000 discrete-time steps by enforcing constant error flow through constant error carousels within special units. Multiplicative gate units learn to open and close access to the constant error flow. LSTM is local in space and time; its computational complexity per time step and weight is O. 1. Our experiments with artificial data involve local, distributed, real-valued, and noisy pattern representations. In comparisons with real-time recurrent learning, back propagation through time, recurrent cascade correlation, Elman nets, and neural sequence chunking, LSTM leads to many more successful runs, and learns much faster. LSTM also solves complex, artificial long-time-lag tasks that have never been solved by previous recurrent network algorithms."], :EP ["1780"], :VL ["9"]}]
           (ris/str->ris-maps (load-ris "one_article.ris")))))
  (testing "Parsing EndNote Online references"
    (let [ms (ris/str->ris-maps (load-ris "endnoteonline.ris"))]
      (is (= 14 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing ERIC_ebsco_1232020_crl_assessment.txt"
    (let [ms (ris/str->ris-maps (load-ris "ERIC_ebsco_1232020_crl_assessment.txt"))]
      (is (= 100 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing Google Scholar RefMan Export"
    (let [ms (ris/str->ris-maps (load-ris "Google_Scholar_RefMan_export.ris"))]
      (is (= 3 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing IEEE Xplore Citation Download"
    (let [ms (ris/str->ris-maps (load-ris "IEEE_Xplore_Citation_Download_2019.11.04.16.41.46.ris"))]
      (is (= 3 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing scopus.ris"
    (let [ms (ris/str->ris-maps (load-ris "scopus.ris"))]
      (is (= 52 (count ms)))
      (is (every? valid-reference? ms))))
  (testing "Parsing zotero.ris"
    (let [ms (ris/str->ris-maps (load-ris "zotero.ris"))]
      (is (= 6 (count ms)))
      (is (every? valid-reference? ms)))))

(deftest ^:unit test-ris-maps->str
  (let [ris->str (fn [filename]
                   (-> filename load-ris ris/str->ris-maps ris/ris-maps->str))]
    (testing "Generating a file with one reference"
      (let [original (str/split (load-ris "one_article_str.ris") #"\n")
            transformed (str/split (ris->str "one_article.ris") #"\n")]
        (is (= original transformed)
            (str "First differing elements: " (compare-sequences original transformed)))))
    (testing "Generating Google Scholar RefMan Export"
      (let [original (str/split (load-ris "Google_Scholar_RefMan_export_str.ris") #"\n")
            transformed (str/split (ris->str "Google_Scholar_RefMan_export.ris") #"\n")]
        (is (= original transformed)
            (str "First differing elements: " (compare-sequences original transformed)))))
    (testing "Generating scopus.ris"
      (let [original (str/split (load-ris "scopus_str.ris") #"\n")
            transformed (str/split (ris->str "scopus.ris") #"\n")]
        (is (= original transformed)
            (str "First differing elements: " (compare-sequences original transformed)))))))
