(ns sysrev.test.fixtures
  (:require
   [sysrev.postgres.interface :as pg]))

(defn execute-one! [system sqlmap]
  (-> system :postgres :datasource
      (pg/execute-one! sqlmap)))

(defn load-stripe-plans! [system]
  (execute-one!
   system
   {:insert-into :stripe-plan
    :on-conflict []
    :do-nothing []
    :values [{:amount 0
              :id "basic_1517864302"
              :interval "month"
              :nickname "Basic"
              :product "prod_CGogIJeUzLDpT5"
              :product-name "Basic"}
             {:amount 0
              :id "price_1JiMtNBnQHYQNUvu9WCddYgT"
              :interval "year"
              :nickname "Unlimited_Org_Annual_free"
              :product "prod_E5w0gncEj2V07N"
              :product-name "Premium"}]}))

(defn load-all-fixtures! [system]
  (doto system
    load-stripe-plans!))
