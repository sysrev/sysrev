(ns sysrev.views.components.brat-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sysrev.views.components.brat :as brat]))

(deftest generate-select-values
  (testing "generate-save-values"
    (let [data #js{:sourceData #js{:text "test" :entities #js["one"] :relations #js[]}}
          save-data (brat/generate-save-data data)]
      ; convert cljs as = doesn't seem to deep compare js objects
      (is (= (js->clj save-data) (js->clj #js{:text "test" :entities #js["one"] :relations #js[]}))))))
