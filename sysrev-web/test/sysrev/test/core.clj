(ns sysrev.test.core
  (:require [clojure.test :refer :all]
            [sysrev.user :refer [started]]))

(defmacro completes? [form]
  `(do ~form true))
