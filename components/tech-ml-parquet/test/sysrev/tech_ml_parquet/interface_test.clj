(ns sysrev.tech-ml-parquet.interface-test
  (:require [clojure.java.io :as io]
            [clojure.test :as test :refer (deftest is testing)]
            [tech.v3.dataset :as ds]
            [tech.v3.libs.parquet :as parquet]))

(deftest test-parquet->ds
  (testing "parquet loading works"
    (let [anatomy (-> (io/resource "sysrev/tech-ml-parquet/CTD_anatomy.parquet")
                      parquet/parquet->ds)]
      (is (= 1844 (ds/row-count anatomy)))
      (is (= {"AnatomyName" "3T3 Cells", "AnatomyID" "MESH:D016475", "Definition" "Cell lines whose original growing procedure consisted being transferred (T) every 3 days and plated at 300,000 cells per plate (J Cell Biol 17:299-313, 1963). Lines have been developed using several different strains of mice. Tissues are usually fibroblasts derived from mouse embryos but other types and sources have been developed as well. The 3T3 lines are valuable in vitro host systems for oncogenic virus transformation studies, since 3T3 cells possess a high sensitivity to CONTACT INHIBITION.", "ParentIDs" "MESH:D002460|MESH:D005347", "TreeNumbers" "A11.251.210.100|A11.329.228.100", "ParentTreeNumbers" "A11.251.210|A11.329.228", "Synonyms" "3T3 Cell|Cell, 3T3|Cells, 3T3"}
             (first (ds/rows anatomy)))))))
