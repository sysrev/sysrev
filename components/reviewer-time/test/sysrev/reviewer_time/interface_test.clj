(ns sysrev.reviewer-time.interface-test
  (:use clojure.test
        #_sysrev.reviewer-time.interface))

(def after-duration (java.time.Duration/ofMinutes 2))
(def idle-duration (java.time.Duration/ofHours 1))

(defn ldt [^String s]
  (java.time.LocalDateTime/parse s))

#_(deftest test-to-intervals
  (testing "Handles empty sequences properly"
    (is (empty? (to-intervals nil)))
    (is (empty? (to-intervals []))))
  (let [interval-keys #{:article-id :project-id :last-event-time :start :end}
        to-intervals* (fn [events] (->> events to-intervals
                                        (map #(select-keys % interval-keys))))]
    (testing "Works correctly on a single event"
      (->> [#:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200002}]
           to-intervals*
           (= [{:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T10:12:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (ldt "2021-07-16T10:14:49")}])
           is))
    (testing "Combines multiple events"
      (->> [#:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:32:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T11:22:49")
                             :project-id 1200002}]
           to-intervals*
           (= [{:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T11:22:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (.plus (ldt "2021-07-16T11:22:49") after-duration)}])
           is))
    (testing "Creates a new interval where article-id changes"
      (->> [#:reviewer-event{:article-id 36900003
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:32:49")
                             :project-id 1200002}]
           to-intervals*
           (= [{:article-id 36900003
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T10:12:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (ldt "2021-07-16T10:32:49")}
               {:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T10:32:49")
                :start (ldt "2021-07-16T10:32:49")
                :end (.plus (ldt "2021-07-16T10:32:49") after-duration)}])
           is))
    (testing "Creates a new interval where project-id changes"
      (->> [#:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200001}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:32:49")
                             :project-id 1200002}]
           to-intervals*
           (= [{:article-id 36900004
                :project-id 1200001
                :last-event-time (ldt "2021-07-16T10:12:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (ldt "2021-07-16T10:32:49")}
               {:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T10:32:49")
                :start (ldt "2021-07-16T10:32:49")
                :end (.plus (ldt "2021-07-16T10:32:49") after-duration)}])
           is))
    (testing "Intervals end after idle-duration"
      (->> [#:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T11:32:49")
                             :project-id 1200002}]
           to-intervals*
           (= [{:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T10:12:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (.plus (ldt "2021-07-16T10:12:49") after-duration)}
               {:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T11:32:49")
                :start (ldt "2021-07-16T11:32:49")
                :end (.plus (ldt "2021-07-16T11:32:49") after-duration)}])
           is)
      (->> [#:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:15:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T11:32:49")
                             :project-id 1200002}]
           to-intervals*
           (= [{:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T10:15:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (.plus (ldt "2021-07-16T10:15:49") after-duration)}
               {:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T11:32:49")
                :start (ldt "2021-07-16T11:32:49")
                :end (.plus (ldt "2021-07-16T11:32:49") after-duration)}])
           is))
    (testing "Handles a series of combinations correctly"
      (->> [#:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:12:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T10:35:49")
                             :project-id 1200002}
            #:reviewer-event{:article-id 36900004
                             :created (ldt "2021-07-16T11:22:49")
                             :project-id 1200002}

            #:reviewer-event{:article-id 36900001
                             :created (ldt "2021-07-16T13:22:49")
                             :project-id 1200002}

            #:reviewer-event{:article-id 36900002
                             :created (ldt "2021-07-16T13:22:52")
                             :project-id 1200001}

            #:reviewer-event{:article-id 36900002
                             :created (ldt "2021-07-16T15:25:50")
                             :project-id 1200001}]
           to-intervals*
           (= [{:article-id 36900004
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T11:22:49")
                :start (ldt "2021-07-16T10:12:49")
                :end (.plus (ldt "2021-07-16T11:22:49") after-duration)}
               {:article-id 36900001
                :project-id 1200002
                :last-event-time (ldt "2021-07-16T13:22:49")
                :start (ldt "2021-07-16T13:22:49")
                :end (ldt "2021-07-16T13:22:52")}
               {:article-id 36900002
                :project-id 1200001
                :last-event-time (ldt "2021-07-16T13:22:52")
                :start (ldt "2021-07-16T13:22:52")
                :end (.plus (ldt "2021-07-16T13:22:52") after-duration)}
               {:article-id 36900002
                :project-id 1200001
                :last-event-time (ldt "2021-07-16T15:25:50")
                :start (ldt "2021-07-16T15:25:50")
                :end (.plus (ldt "2021-07-16T15:25:50") after-duration)}])
           is))))
