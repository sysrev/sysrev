(ns sysrev.views.panels.project.define-labels-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sysrev.views.panels.project.define-labels :as lb]))

(def LABEL1 {:label1 {:value-type "relationship" :enabled true}})
(def LABEL2 {:label2 {:value-type "relationship" :enabled false}})
(def LABEL3 {:label3 {:value-type "other" :enabled true}})

(deftest no-relation
  (testing "when no relationship exists"
    (let [label-vals LABEL3]
      (is (= false (lb/has-relationship-label? label-vals))))))

(deftest with-relation
  (testing "when relationship exists"
    (let [label-vals LABEL1]
      (is (= true (lb/has-relationship-label? label-vals))))))

(deftest with-relation-not-active
  (testing "when-relationship exists but not active"
    (let [label-vals LABEL2]
      (is (= false (lb/has-relationship-label? label-vals))))))

(deftest with-both-exists
  (testing "when-relationship exists but not active and one exists with active"
    (let [label-vals (merge LABEL1 LABEL2 LABEL3)]
      (is (= true (lb/has-relationship-label? label-vals))))))
