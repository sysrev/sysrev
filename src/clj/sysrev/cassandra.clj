(ns sysrev.cassandra
  (:require [qbits.alia :as alia]
            [qbits.hayt :as h :refer :all :exclude [update group-by]]))

(defonce active-cluster (atom nil))
(defonce active-session (atom nil))

(defn connect-db [& {:keys [hosts]
                     :or {hosts ["api.insilica.co"]}}]
  (reset! active-cluster (alia/cluster {:contact-points hosts
                                        #_ :pooling-options
                                        #_ {:pool-timeout-millis 1000}
                                        :socket-options
                                        {:connect-timeout 1000
                                         :read-timeout 30000}}))
  (reset! active-session (alia/connect @active-cluster)))

(defn execute [& args]
  (apply alia/execute @active-session args))

(defn get-keyspaces []
  (execute "select * from system_schema.keyspaces"))

(defn get-pmids-xml [pmids]
  (->> (select :biosource.pubmed
               (where [[in :pmid (vec pmids)]])
               (columns :xml))
       (execute)
       (map :xml)))
