(ns sysrev.views.components.relationship-builder-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sysrev.views.components.relationship-builder :as rlb]))

(deftest generate-select-values
  (testing "generate-select-values"
    (let [label-vals `("test1" "test2")
          generated-values (rlb/generate-select-values label-vals)]
      (is (= generated-values `({:text "test1" :value "test1"} {:text "test2" :value "test2"}))))))

(deftest filter-rows
  (testing "filter-rows")
  (let [rows [{:key 1} {:key 2} {:key 3} {:key 4}]
        filtered-rows (rlb/filter-rows rows 3)]
    (is (= filtered-rows [{:key 1} {:key 2} {:key 4}]))))

(deftest add-row
  (testing "add-rows"
    (let [relationships (atom [])]
      (rlb/add-row relationships)
      (is (= @relationships [{:from nil :to nil :value nil :key 1}]))
      (rlb/add-row relationships)
      (is (= @relationships [{:from nil :to nil :value nil :key 1} {:from nil :to nil :value nil :key 2}])))))

(deftest remove-row
  (testing "remove-rows"
    (let [relationships (atom [{:from nil :to nil :value nil :key 1} {:from nil :to nil :value nil :key 2}])]
      (rlb/remove-row relationships 2)
      (is (= @relationships [{:from nil :to nil :value nil :key 1}]))
      (rlb/remove-row relationships 2)
      (is (= @relationships [{:from nil :to nil :value nil :key 1}]))
      (rlb/remove-row relationships 1)
      (is (= @relationships [])))))
