(ns datapub.ctgov
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [sysrev.datapub-client.interface.queries :as dpcq]
            [sysrev.util-lite.interface :as ul])
  (:import [java.time Duration ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def study-url "https://clinicaltrials.gov/api/query/full_studies")
(def rss-feed-url "https://clinicaltrials.gov/ct2/results/rss.xml")

(def datetime-formatter
  (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss z"))

(defn parse-datetime [s]
  (ZonedDateTime/parse s datetime-formatter))

(defn get-new-studies []
  (ul/retry
   {:interval-ms 1000 :n 10}
   (client/get rss-feed-url {:query-params {:rcv_d 3 :count 10000}})))

(defn get-recent-updates []
  (ul/retry
   {:interval-ms 1000 :n 10}
   (client/get rss-feed-url {:query-params {:lup_d 3 :count 10000}})))

(defn rss->items [rss]
  (->> rss
       xml/parse-str
       :content
       (mapcat :content)
       (filter #(= :item (:tag %)))))

(defn item-guid [item]
  (some #(when (= :guid (:tag %)) (first (:content %))) (:content item)))

(defn item-pubdate [item]
  (some
   #(when (= :pubDate (:tag %))
      (-> % :content first parse-datetime))
   (:content item)))

(defn get-study [guid]
  (ul/retry
   {:interval-ms 1000 :n 10}
   (let [r (client/get study-url {:as :json
                                  :query-params {:expr guid :fmt "JSON"}})
         full-studies (get-in r [:body :FullStudiesResponse :FullStudies])]
     (when (not= 1 (count full-studies))
       (throw (ex-info "Unexpected response." {:response r})))
     (:Study (first full-studies)))))

(defn get-study-nctid [study]
  (get-in study [:ProtocolSection :IdentificationModule :NCTId]))

(defn get-new-updated-studies []
  (let [start-dt (.minus (ZonedDateTime/now) (Duration/ofDays 3))]
    (->> (get-new-studies)
         :body
         rss->items
         (concat (rss->items (:body (get-recent-updates))))
         (filter #(.isBefore start-dt (item-pubdate %)))
         (map item-guid)
         distinct
         (map get-study))))

(defn graphql-request [url query & [variables options]]
  (client/post
   url
   (merge
    options
    {:as :json
     :content-type :json
     :form-params {:query query
                   :variables variables}})))

(defn import-study! [url {:keys [auth-header dataset-id study]}]
  (ul/retry
   {:interval-ms 1000 :n 10}
   (graphql-request
    url
    (dpcq/m-create-dataset-entity "id")
    {:input
     {:content (json/generate-string study)
      :datasetId dataset-id
      :externalId (get-study-nctid study)
      :mediaType "application/json"}}
    {:headers
     {"Authorization" auth-header}})))

(defn get-num-studies-available []
  (-> (client/get study-url {:as :json
                             :query-params {:fmt "json"
                                            :max_rnk 1
                                            :min_rnk 1}})
      :body :FullStudiesResponse :NStudiesAvail))

(defn get-studies-in-range [min-rnk max-rnk]
  (cond
    (> 1 (- max-rnk min-rnk))
    nil

    (> 100 (- max-rnk min-rnk))
    (ul/retry
     {:interval-ms 1000 :n 10}
     (->> (client/get study-url {:as :json
                                 :query-params {:fmt "json"
                                                :max_rnk max-rnk
                                                :min_rnk min-rnk}})
          :body :FullStudiesResponse :FullStudies
          (map :Study)))

    :else
    (->> (concat (range min-rnk (inc max-rnk) 100) [(inc max-rnk)])
         (partition 2 1)
         (mapcat (fn [[a b]] (get-studies-in-range a (dec b)))))))

(defn import-new-updated-studies! []
  (doseq [study (get-new-updated-studies)]
    (prn
     (import-study!
      "http://localhost:8888/api"
      {:auth-header (str "Bearer " (System/getenv "SYSREV_DEV_KEY"))
       :dataset-id 1
       :study study}))))

(comment
  (def url "http://localhost:8888/api")
  (graphql-request
   url
   (dpcq/m-create-dataset "id")
   {:input
    {:description "ClinicalTrials.gov is a database of privately and publicly funded clinical studies conducted around the world."
     :name "ClinicalTrials.gov"
     :public true}})

  ;; Initial import of all studies.
  (doseq [study (get-studies-in-range 1 (get-num-studies-available))]
    (prn
     (import-study! url
                    {:auth-header (str "Bearer " (System/getenv "SYSREV_DEV_KEY"))
                     :dataset-id 1
                     :study study})))

  ;; Daily updates.
  (doseq [study (get-new-updated-studies)]
    (prn
     (import-study! url {:auth-header (str "Bearer " (System/getenv "SYSREV_DEV_KEY"))
                         :dataset-id 1
                         :study study}))))


