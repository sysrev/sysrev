(ns sysrev.graphql.token-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [medley.core :as medley]
            [sysrev.test.core :as test]
            [venia.core :as venia]))

(defn getTokenInfo! [system token return & {:as opts}]
  (test/graphql-request
   system
   (venia/graphql-query
    {:venia/queries
     [[:getTokenInfo {:token token} return]]})
   opts))

(deftest ^:integration test-getTokenInfo
  (test/with-test-system [system {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)]
      (is (= {:data {:getTokenInfo nil}}
             (getTokenInfo! system (str (random-uuid)) [:userId]))
          "Random tokens return nothing")
      (is (= {:userId (str user-id)}
             (-> (getTokenInfo! system api-token [:userId])
                 :data :getTokenInfo))
          "userId can be retrieved with getTokenInfo"))))
