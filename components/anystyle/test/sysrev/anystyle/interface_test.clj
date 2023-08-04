(ns sysrev.anystyle.interface-test
  (:require [clojure.test :as test :refer [deftest is]]
            [sysrev.anystyle.interface :as anystyle]))

(deftest test-parse-str
  (is (= [{:author [{:family "Abdel Rahman", :given "R.O."} {:family "Hung", :given "Y.T."}], :date ["2020"], :title ["Irradiation technologies for wastewater treatment: a review"], :volume ["13"], :pages ["3576–3598"], :type "article-journal", :container-title ["Arabian Journal of Chemistry"], :issue ["4"]} {:author [{:family "Ahmad", :given "A."} {:family "Hameed", :given "B.H."}], :date ["2019"], :title ["Biochar as a green adsorbent for the removal of antibiotics: A review"], :volume ["8"], :pages ["100312"], :type "article-journal", :container-title ["Bioresource Technology Reports"]} {:author [{:family "Ahmad", :given "M."} {:family "Rajapaksha", :given "A.U."} {:family "Lim", :given "J.E."} {:family "Zhang", :given "M."} {:family "Bolan", :given "N."} {:family "Mohan", :given "D."} {:family "Vithanage", :given "M."} {:family "Lee", :given "S.S."} {:family "Ok", :given "Y.S."}], :date ["2014"], :title ["Biochar as a sorbent for contaminant management in soil and water: A review"], :volume ["99"], :pages ["19–33"], :type "article-journal", :container-title ["Chemosphere"]}]
         (anystyle/parse-str
          "http://127.0.0.1:4567/api/v1"
          "Abdel Rahman, R. O., & Hung, Y. T. (2020). Irradiation technologies for wastewater treatment: a review. Arabian Journal of Chemistry, 13(4), 3576-3598.

Ahmad, A., & Hameed, B. H. (2019). Biochar as a green adsorbent for the removal of antibiotics: A review. Bioresource Technology Reports, 8, 100312.

Ahmad, M., Rajapaksha, A. U., Lim, J. E., Zhang, M., Bolan, N., Mohan, D., Vithanage, M., Lee, S. S., & Ok, Y. S. (2014). Biochar as a sorbent for contaminant management in soil and water: A review. Chemosphere, 99, 19–33.
"))))
