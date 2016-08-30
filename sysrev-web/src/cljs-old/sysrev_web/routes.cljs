(ns sysrev-web.routes
  (:require [sysrev-web.base :refer [state server-data nav! notify label-queue label-queue-right-append]]
            [secretary.core :include-macros true :refer-macros [defroute]]
            [ajax.core :refer [GET POST]]))

;; This var records the elements of `server-data` that are required by
;; a page on the web site, so that rendering can be delayed until
;; all the required data has been received.
(defonce page-data-fields
  {:home
   [[:criteria] [:ranking] [:articles] [:labels]]
   :users
   [[:users]]})

(defn integerify-map-keys
  "Server-side maps with integer keys will have the keys changed to keywords
   when converted to JSON. This converts any integer keywords back to integers."
  [m]
  (->> m
       (mapv (fn [[k v]]
               (let [k-int (js/parseInt (name k))
                     k-new (if (integer? k-int) k-int k)]
                 [k-new v])))
       (apply concat)
       (apply hash-map)))

(defn ajax-get
  "Performs an AJAX GET call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (GET url
        :format :json
        :response-format :json
        :keywords? true
        :params content
        :handler #(handler (integerify-map-keys %))))
  ([url handler] (ajax-get url nil handler)))

(defn ajax-post
  "Performs an AJAX POST call with proper JSON handling.
  Calls function `handler` on the response map."
  [url content handler]
  (POST url
        :format :json
        :response-format :json
        :keywords? true
        :params content
        :handler #(handler (integerify-map-keys %))))

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= :not-found (get-in @server-data % :not-found))
            required-fields)))

(defn pull-identity []
  (ajax-get "/api/auth/identity"
            (fn [response]
              (swap! state assoc :identity response))))

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (ajax-get "/api/criteria"
              (fn [response]
                (swap! server-data assoc
                       :criteria response)))))

(defn pull-all-labels []
  (ajax-get "/api/all-labels"
            (fn [response]
              (swap! server-data assoc :labels response))))

(defn pull-labeled-articles []
  (ajax-get "/api/labeled-articles"
            (fn [response]
              (swap! server-data assoc :labeled-articles response)
              (swap! server-data update :articles #(merge % response)))))

(defn pull-ranking-page [num]
  (when true ;; (nil? (get-in @server-data [:ranking :pages num]))
    (ajax-get (str "/api/ranking/" num)
              (fn [response]
                (let [ranked-ids
                      (->> response
                           (sort-by (comp :score second))
                           (mapv first))]
                  (swap! server-data
                         #(assoc % :articles
                                 (merge (:articles %) response)))
                  (swap! server-data assoc-in
                         [:ranking :pages num] ranked-ids))))))

(defn pull-project-users []
  (ajax-get "/api/project-users"
            (fn [response]
              (swap! server-data assoc :users response))))

(defn pull-initial-data []
  (when (nil? (:criteria @server-data)) (pull-criteria))
  (when (nil? (:labels @server-data)) (pull-all-labels))
  (when (nil? (:labeled-articles @server-data)) (pull-labeled-articles))
  (pull-identity)
  (when (nil? (:users @server-data)) (pull-project-users))
  (let [rpage (:ranking-page @state)]
    (when (and (not (nil? rpage)) (nil? (:ranking @server-data))) (pull-ranking-page rpage))))


(defn set-page! [key] (swap! state assoc :page key))

(defroute home "/" []
  (pull-initial-data)
  (set-page! :home))


(defroute login "/login" []
  (set-page! :login))

(defroute register "/register" []
  (set-page! :register))

;; Below routes require login to function.
;; Todo: Redirect to / if not logged in.
(defroute users "/users" []
  (pull-initial-data)
  ;; Re-fetch :users data in case it has changed
  (when (:users @server-data)
    (pull-project-users))
  (set-page! :users))

(defroute current-user "/user" []
  (pull-initial-data)
  (swap! state assoc-in [:user :display-id] (-> @state :identity :id))
  (set-page! :user))

(defroute user "/user/:id" [id]
  (pull-initial-data)
  (swap! state assoc-in [:user :display-id] (js/parseInt id))
  (set-page! :user))

(defroute classify "/classify" []
  (pull-initial-data)
  (set-page! :classify))

(defroute labels "/labels" []
  (pull-initial-data)
  (set-page! :labels))

(defn get-ranking-article-ids [page-num]
  (get-in @server-data [:ranking :pages page-num]))


(defn critbyId [criteria id]
  (let [rcrit (first (filter #(= id (:id %)) criteria))
        res (when-not (empty? rcrit) (:answer rcrit))]
    res))


(defn get-ui-filtered-article-ids []
  (let [af (:article-filter @state)
        articles (:articles @server-data)
        res-keys
        ;; here we get a list of keys based on the ui filters.
        ;; If no filter chosen, just get all the article keys.
        (if (empty? af)
          (keys (:articles @server-data))
          (let [in-cids (set (keys (filter val af)))
                ex-cids (map first (filter #(-> % val false?) af))
                acs (:labels @server-data)
                meets-criteria
                (fn [criteria]
                  (and ;;Could simplify the logic here.
                   (every? #(critbyId criteria (int (name %))) in-cids)
                   (not-any? #(critbyId criteria (int (name %))) ex-cids)))
                filter-fn (fn [[_ criteria]] (meets-criteria criteria))]
            (keys (filter filter-fn acs))))]
                                        ; Sort list of keys by their ranking score.
    (sort-by #(get-in articles [% :score]) res-keys)))

(defn get-classified-ids []
  (-> @server-data :labels keys))

(defn get-article-criteria [id]
  (->> (get-in @server-data [:labels id])
       (sort-by :criteria_id)))

(defn get-article [id]
  (let [article (get-in @server-data [:articles id])]
    (when article
      (assoc article :criteria (get-article-criteria id)))))

#_
(defn auth-failure [err]
  (swap! @state assoc :form-error err))

(defn post-login [email password]
  (ajax-post "/api/auth/login"
             {:email email :password password}
             (fn [_]
               (nav! home))))

(defn post-register [email password]
  (ajax-post "/api/auth/register"
             {:email email :password password}
             (fn [_]
               (post-login email password))))

(defn post-logout []
  (ajax-post "/api/auth/logout"
             nil
             (fn [response]
               (swap! state dissoc :identity)
               (nav! home)
               (notify "Logged out."))))

(defn submit-tag [{:keys [article-id criteria-id value]}]
  (ajax-post "/api/tag"
             {:articleId article-id
              :criteriaId criteria-id
              :value value}
             (fn [response]
               (notify "Tag sent"))))

(defn pull-label-tasks
  ([interval handler after-score]
   (ajax-get (str "/api/label-task/" interval)
             (when-not (nil? after-score) {:greaterThanScore after-score})
             (fn [response]
               (let [result (:result response)]
                 (notify (str "Fetched " (count result) " more articles"))
                 (handler result)))))
  ([interval handler] (pull-label-tasks interval handler 0.0)))

(defn label-queue-update
  ([min-length interval]
   (let [cur-len (count (label-queue))
         deficit (- min-length cur-len)
         fetch-num (if (> deficit 0) (max deficit interval) 0)
         max-dist-score (if (empty? (label-queue)) nil (:score (last (label-queue))))]
     (when (> fetch-num 0)
       (println (str "fetching scores greater than " max-dist-score))
       (pull-label-tasks fetch-num label-queue-right-append max-dist-score))))
  ([] (label-queue-update 5 1)))

(defn send-tags
  "send the criteria responses for a given article.
  Takes an article-id and a map of criteria-ids to booleans.
  Any unset criteria-ids will be unset on the server."
  [article-id criteria-values]
  (let [data (->> criteria-values
                  (map (fn [[cid, value]]
                         {:articleId article-id
                          :criteriaId (js/parseInt (name cid))
                          :value value})))]
    (ajax-post "/api/tags"
               {:tags data}
               (fn [response]
                 (let [err (:err response)
                       res (:result response)]
                   (when-not (empty? err) (notify (str "Error: " err)))
                   (when-not (empty? res) (notify "Tags saved")))))))
