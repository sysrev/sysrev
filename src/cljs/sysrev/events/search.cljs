(ns sysrev.events.search
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v]]
   [sysrev.subs.project :as project]
   [sysrev.subs.auth :as auth]))

;; A DB map representing a search term in :data :search-term <term>
;;
;;{:count <integer> ; total amount of documents that match a search term
;; :pages {<page-number> ; an integer
;;         {:pmids [PMIDS] ; a vector of PMID integers associated with page_no
;;          :summaries {<pmid> ; an integer, should be in [PMIDS] vector above
;;                      { PubMed Summary map} ; contains many key/val pairs
;;                     }
;;         }
;;}
(reg-event-fx
 :pubmed/save-search-term-results
 [trim-v]
 ;; WARNING: This fn must return something (preferable the db map),
 ;;          otherwise the system will hang!!!
 (fn [{:keys [db]} [search-term page-number search-term-response]]
   (let [pmids (:pmids search-term-response)
         page-inserter
         ;; We only want to insert a {page-number [pmids]} map
         ;; if there are actually pmids for that page-number
         ;; associated with the search term
         ;; e.g. if you ask /api/pubmed/search for page-number 30 of the term "foo bar"
         ;; you will get back an empty vector. This fn discards that response
         (fn [db] (update-in db [:data :pubmed-search search-term :pages]
                             #(let [data {page-number {:pmids pmids}}]
                                ;; on the first request, the :pages keyword
                                ;; doesn't yet exist so conj will return a list
                                ;; and not a map. This makes sure only maps
                                ;; are saved in our DB
                                (if (nil? %)
                                  data
                                  (conj % data)))))]
     (if-not (empty? pmids)
       {:db (-> db
                ;; include the count
                (assoc-in [:data :pubmed-search search-term :count]
                          (:count search-term-response))
                ;; include the page-number and associated pmids
                page-inserter)
        :dispatch [:require [:pubmed-summaries search-term page-number pmids]]}
       {:db (assoc-in db [:data :pubmed-search search-term :count]
                      (:count search-term-response))}))))


(reg-event-db
 :pubmed/save-search-term-summaries
 [trim-v]
 (fn [db [search-term page-number response]]
   (assoc-in db [:data :pubmed-search search-term :pages page-number :summaries]
             response)))
