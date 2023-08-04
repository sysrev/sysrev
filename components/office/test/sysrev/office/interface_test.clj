(ns sysrev.office.interface-test
  (:require [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is]]
            [sysrev.office.interface :as office]))

(deftest test-get-docx-text
  (is (= "Abdel Rahman, R. O., & Hung, Y. T. (2020). Irradiation technologies for wastewater treatment: a review. Arabian Journal of Chemistry, 13(4), 3576-3598.

Ahmad, A., & Hameed, B. H. (2019). Biochar as a green adsorbent for the removal of antibiotics: A review. Bioresource Technology Reports, 8, 100312.

Ahmad, M., Rajapaksha, A. U., Lim, J. E., Zhang, M., Bolan, N., Mohan, D., Vithanage, M., Lee, S. S., & Ok, Y. S. (2014). Biochar as a sorbent for contaminant management in soil and water: A review. Chemosphere, 99, 19–33.
"
         (-> "sysrev/office/test/refs.docx" io/resource io/input-stream office/get-docx-text))))
