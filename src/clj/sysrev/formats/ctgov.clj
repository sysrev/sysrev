(ns sysrev.formats.ctgov
  (:require [clojure.string :as str]
            [clj-http.client :as client]))

(def ct-gov-root "https://clinicaltrials.gov/api")

(defn search [q p]
  (let [page-size 10
        max-rank (* p page-size)
        min-rank (- max-rank (- page-size 1))
        resp (-> (client/get
                  (str ct-gov-root "/query/study_fields")
                  ;; https://clinicaltrials.gov/api/gui/ref/expr
                  ;; https://clinicaltrials.gov/api/info/search_areas?fmt=XML
                  ;; https://clinicaltrials.gov/api/info/study_structure?fmt=XML
                  {:query-params
                   {:expr q
                    ;; (str q " AND SEARCH[Location](AREA[LocationCity]Portland AND AREA[LocationState]Maine)")
                    ;; (str "AREA[NCTId]NCT04050163")
                    :fields (str/join "," ["Condition"
                                           "NCTId"
                                           "OverallStatus"
                                           "BriefTitle"
                                           "InterventionName"
                                           "InterventionType"
                                           "LocationFacility"
                                           "LocationState"
                                           "LocationCountry"
                                           "LocationCity"])
                    :min_rnk min-rank
                    :max_rnk max-rank
                    :fmt "JSON"}
                   :as :json
                   :throw-exceptions false})
                 :body)
        studies (get-in resp [:StudyFieldsResponse :StudyFields])
        extract-studies (fn [{:keys [BriefTitle Condition LocationFacility LocationCity
                                     LocationState LocationCountry InterventionType
                                     InterventionName OverallStatus NCTId]}]
                          {:title (first BriefTitle)
                           :status (first OverallStatus)
                           :study-title (first BriefTitle)
                           :conditions Condition
                           :interventions {:type InterventionType
                                           :name InterventionName}
                           :locations [LocationFacility LocationCity LocationState LocationCountry]
                           :nctid (first NCTId)})]
    {:results (map extract-studies studies)
     :count (-> (get-in resp [:StudyFieldsResponse :NStudiesFound])
                (or 0))}))

(defn get-nctids-for-query
  "Given a search query, return a vector of NCTIds which make the query"
  [q]
  (let [total-studies (:count (search q 1))
        thousands (int (/ total-studies 1000))]
    (->> (for [i (range 0 (+ 1 thousands))]
           (-> (client/get (str ct-gov-root "/query/study_fields")
                           {:query-params {:expr q
                                           :fields (clojure.string/join "," ["NCTId" "BriefTitle"])
                                           :min_rnk (+ 1 (* i 1000))
                                           :max_rnk (* (+ i 1) 1000)
                                           :fmt "json"}
                            :as :json
                            :throw-exceptions false})
               :body
               (get-in [:StudyFieldsResponse :StudyFields])))
         (apply concat)
         vec)))
