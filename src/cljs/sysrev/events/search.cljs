(ns sysrev.events.search
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v]]
   [sysrev.subs.project :as project]
   [sysrev.subs.auth :as auth]))

(reg-event-db
 :pubmed/save-search-term-results
 [trim-v]
 (fn [db [search-term page search-term-response]]
   (let [page-inserter
         ;; We only want to insert a {page [pmids]} map
         ;; if there are actually pmids for that page
         ;; associated with the search term
         ;; e.g. if you ask /api/pubmed/search for page 30 of the term "foo bar"
         ;; you will get back an empty vector. This fn discards that response
         (fn [db] (if (not (empty? (:pmids search-term-response)))
                    (update-in db [:data :search-term search-term :pages]
                               #(let [data {page {:pmids (:pmids search-term-response)}}]
                                  ;; on the first request, the :pages keyword
                                  ;; doesn't yet exist so conj will return a list
                                  ;; and not a map. This makes sure only maps
                                  ;; are saved in our DB
                                  (if (nil? %)
                                    data
                                    (conj % data))))
                    db))]
     (-> db
         ;; include the count
         (assoc-in [:data :search-term search-term :count]
                   (:count search-term-response))
         ;; include the page and associated pmids
         page-inserter))))

(reg-event-db
 :pubmed/save-search-term-summaries
 [trim-v]
 (fn [db []]))
