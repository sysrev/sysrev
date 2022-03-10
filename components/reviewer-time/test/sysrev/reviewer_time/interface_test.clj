(ns sysrev.reviewer-time.interface-test
  (:require [clojure.test :refer :all]
            [sysrev.reviewer-time.interface :as reviewer-time]))

(def after-duration (java.time.Duration/ofMinutes 2))
(def idle-duration (java.time.Duration/ofHours 1))

(defn ldt [^String s]
  (java.time.LocalDateTime/parse s))

(deftest ^:unit test-to-intervals
  (testing "Handles empty sequences properly"
    (is (empty? (reviewer-time/to-intervals nil)))
    (is (empty? (reviewer-time/to-intervals []))))
  (let [interval-keys #{:article-id :project-id :last-event-time :start :end}
        to-intervals* (fn [events] (->> events reviewer-time/to-intervals
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
    (let [start (ldt "2021-07-16T10:12:49")
          start+3 (.plus start (java.time.Duration/ofMinutes 3))
          after-idle (-> (.plus start idle-duration)
                         (.plus (java.time.Duration/ofMinutes 5)))]
      (testing "Intervals end after idle-duration"
        (->> [#:reviewer-event{:article-id 36900004
                               :created start
                               :project-id 1200002}
              #:reviewer-event{:article-id 36900004
                               :created after-idle
                               :project-id 1200002}]
             to-intervals*
             (= [{:article-id 36900004
                  :project-id 1200002
                  :last-event-time start
                  :start start
                  :end (.plus start after-duration)}
                 {:article-id 36900004
                  :project-id 1200002
                  :last-event-time after-idle
                  :start after-idle
                  :end (.plus after-idle after-duration)}])
             is)
        (->> [#:reviewer-event{:article-id 36900004
                               :created start
                               :project-id 1200002}
              #:reviewer-event{:article-id 36900004
                               :created start+3
                               :project-id 1200002}
              #:reviewer-event{:article-id 36900004
                               :created after-idle
                               :project-id 1200002}]
             to-intervals*
             (= [{:article-id 36900004
                  :project-id 1200002
                  :last-event-time start+3
                  :start start
                  :end (.plus start+3 after-duration)}
                 {:article-id 36900004
                  :project-id 1200002
                  :last-event-time after-idle
                  :start after-idle
                  :end (.plus after-idle after-duration)}])
             is)))
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
