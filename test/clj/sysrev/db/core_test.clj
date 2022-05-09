(ns sysrev.db.core-test
  (:require [clojure.test :refer [deftest is]]
            [sysrev.db.core :as db]
            [sysrev.test.core :as test]))

(defn tx!
  "Executes a transaction designed to create serialization failures
   when run concurrently with itself."
  [sr-context]
  (db/with-tx [sr-context sr-context]
    (->> {:select :%count.* :from :web-user}
         (db/execute-one! sr-context)
         :count)
    (Thread/sleep 50)
    (->> {:insert-into :web-user
          :values [{:api-token (str (random-uuid))
                    :email (str (random-uuid) "@example.com")
                    :username (str (random-uuid))}]}
         (db/execute-one! sr-context)
         :next.jdbc/update-count)))

(deftest ^:integration test-retry-serial
  (test/with-test-system [{:keys [sr-context]} {}]
    (let [txs [(future (tx! sr-context)) (future (tx! sr-context))]]
      (is (thrown-with-msg?
           Exception
           ; normally PSQLException, but wrapped in
           ; java.util.concurrent.ExecuctionException
           ; because of the future
           #"could not serialize access"
           [@(first txs) @(second txs)])
          "Serialization failures without retry throw exceptions"))
    (let [sr-context (assoc-in sr-context [:tx-retry-opts :n] 1)
          txs [(future (tx! sr-context)) (future (tx! sr-context))]]
      (is (= [1 1] [@(first txs) @(second txs)])
          "Serialization failures are retried and the retry succeeds"))
    (let [sr-context (assoc-in sr-context [:tx-retry-opts :n] 4)
          txs (->> #(future (tx! sr-context))
                   (repeatedly 4)
                   doall
                   (map deref))]
      (is (every? #{1} txs)
          "Several concurrent serialization failures can be retried successfully"))))
