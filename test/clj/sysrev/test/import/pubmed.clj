(ns sysrev.test.import.pubmed
  (:require
   [clojure.test :refer :all]
   [clojure.java.jdbc :as jdbc]
   [sysrev.import.pubmed :refer [fetch-pmid-xml parse-pmid-xml import-pmids-to-project get-query-pmids]]
   [sysrev.util :refer [parse-xml-str xml-find]]
   [sysrev.db.core :refer [*conn*]]
   [sysrev.db.project :as project]
   [clojure.string :as str]))

(def db-spec  {:classname "org.postgresql.Driver"
               :subprotocol "postgresql"
               :subname "//localhost:5432/sysrev_test"
               :user "postgres"
               :password ""})

(defn database-rollback-fixture [test]
  (jdbc/with-db-transaction [db db-spec]
    (jdbc/db-set-rollback-only! db)
    (binding [*conn* db] ;; rebind dynamic var db, used in tests
      (test))))

(use-fixtures :each database-rollback-fixture)

(deftest retrieve-article
  (let [result-count (fn [result] (-> result first :count))
        current-count (result-count (jdbc/query *conn* "SELECT count(*) FROM article"))
        pmids (get-query-pmids "foo bar")
        first-pmid (first pmids)
        new-project (project/create-project "test project")
        new-project-id (:project-id new-project)]
    ;;(println (type new-project-id))
    (import-pmids-to-project (get-query-pmids "foo bar") new-project-id)
    (is (= (count pmids)
           (project/project-article-count new-project-id)))))


(def ss (partial clojure.string/join "\n"))

(def xml-vector-node
  (ss ["<doc>"
         "<test>"
           "<A>1</A>"
           "<A>2</A>"
           "<A>3</A>"
         "</test>"
       "</doc>"]))


(deftest find-xml-test
  (let [pxml (parse-xml-str xml-vector-node)
        els (xml-find pxml [:test :A])]
    (is (= (count els) 3))))

(deftest parse-pmid-test
  (let [xml (fetch-pmid-xml 11592337)
        parsed (parse-pmid-xml xml)]
    (is (str/starts-with? (:abstract parsed) "OBJECTIVE: To determine"))
    (is (str/includes? (:abstract parsed) "\n\nSAMPLE POPULATION: Corneal"))
    (is (= (str/join "; " (:authors parsed)) "Hendrix, DV.; Ward, DA.; Barnhill, MA."))
    (is (= (:public-id parsed) "11592337"))))

; Fails to get public-id for this pubmed article
(deftest parse-pmid-test-2
  (let [xml (fetch-pmid-xml 28280522)
        parsed (parse-pmid-xml xml)]
    (is (= (:public-id parsed) "28280522"))))
  
