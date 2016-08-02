(ns sysrev-web.routes
  (:require [sysrev-web.base :refer [state server-data nav!]]
            [secretary.core :include-macros true :refer-macros [defroute]]
            [ajax.core :refer [GET POST]]))

(defonce page-data-fields
         {:home
          [[:criteria] [:ranking] [:articles] [:articles-criteria]]
          :users
           [[:users]]})


(defn ajax-get [url handler]
  (GET url
       :format :json
       :response-format :json
       :keywords? true
       :handler handler))

(defn ajax-post [url content handler]
  (POST url
        :format :json
        :response-format :json
        :keywords? true
        :params content
        :handler handler))


(defn data-initialized? [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= :not-found (get-in @server-data % :not-found))
            required-fields)))


(defn pull-user-status []
  (ajax-get "/api/user"
            (fn [response]
              (swap! server-data assoc :user (:result response)))))

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (ajax-get "/api/criteria"
              (fn [response]
                (swap! server-data assoc
                       :criteria (:result response))))))

(defn pull-articles-criteria []
  (ajax-get "/api/allcriteria"
            (fn [response]
              (let [result (:result response)
                    {articles :articles criteria :criteria} result]
                (swap! server-data assoc
                       :articles-criteria criteria)
                (swap! server-data update :articles #(merge % articles))))))

(defn pull-ranking-page [num]
  (when (nil? (get-in @server-data [:ranking :pages num]))
    (ajax-get (str "/api/ranking/" num)
              (fn [response]
                (let [mapified (:result response)]
                  (swap! server-data
                         #(assoc % :articles
                                   (merge (:articles %) (:entries mapified))))
                  (swap! server-data assoc-in
                         [:ranking :pages num] (:ids mapified)))))))


(defn pull-users-data []
  (ajax-get "/api/users"
            (fn [response]
              (swap! server-data assoc :users (:result response)))))

(defn pull-initial-data []
  (when (nil? (:criteria @server-data)) (pull-criteria))
  (when (or (nil? (:articles @server-data)) (nil? (:articles-criteria @server-data))) (pull-articles-criteria))
  (when (nil? (:user @server-data)) (pull-user-status))
  (when (nil? (:users @server-data)) (pull-users-data))
  (let [rpage (:ranking-page @state)]
    (when (and (not (nil? rpage)) (nil? (:ranking @server-data))) (pull-ranking-page rpage))))


(defn set-page! [key] (swap! state assoc :page key))

(defroute home "/" []
  (pull-initial-data)
  (set-page! :home))

(defroute current-user "/user" []
          (pull-initial-data)
          (swap! state assoc-in [:user :display-id] (-> @server-data :user :id))
          (set-page! :user))

(defroute user "/user/:id" [id]
          (pull-initial-data)
          (swap! state assoc-in [:user :display-id] id)
          (set-page! :user))

(defroute login "/login" []
  (set-page! :login))

(defroute register "/register" []
  (set-page! :register))

(defroute users "/users" []
          (set-page! :users))

(defroute classify "/classify" []
          (set-page! :classify))


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
                acs (:articles-criteria @server-data)
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
  (let [af (:articles-criteria @server-data)]
    (map #(-> % name int) (keys af))))

(defn get-article-criteria [id]
  (->> (get-in @server-data [:articles-criteria id])
       (sort-by :id)))

(defn get-article [id]
  (let [article (get-in @server-data [:articles id])]
    (when article
      (assoc article :criteria (get-article-criteria id)))))


(defn auth-success [result]
  (swap! server-data assoc :user result)
  (pull-user-status)
  (nav! home))

(defn auth-failure [err]
  (swap! @state assoc :form-error err))

(defn auth-response [response]
  (if (nil? (:err response))
    (auth-success (:result response))
    (auth-failure (:err response))))

(defn post-login [data]
  (ajax-post "/api/auth/login"
             data
             auth-response))

(defn post-register [data]
  (ajax-post "/api/auth/register"
             data
             auth-response))

(defn post-logout []
  (ajax-post "/api/auth/logout"
             nil
             (fn [response]
               (swap! server-data dissoc :user)
               (nav! home))))



