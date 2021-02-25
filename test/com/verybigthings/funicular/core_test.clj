(ns com.verybigthings.funicular.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [com.verybigthings.funicular.core :as core]
            [malli.core :as m]
            ))

(s/check-asserts true)

(def schema-registry
  (merge
    (m/default-schemas)
    {}))

(defn baz-bar [])

(defn left-odd? [{:keys [data] :as req}]
  (odd? (:left data)))

(defn right-even? [{:keys [data]}]
  (even? (:right data)))

(deftest basic
  (let [api      {:context [:foo {:commands {:bar {:input-schema :any
                                                   :output-schema :any
                                                   :handler (fn [_] 2)}}}
                            [:bar {:rules left-odd?
                                   :queries {:query {:input-schema :any
                                                     :output-schema :any
                                                     :rules [:and left-odd? right-even?
                                                             [:or
                                                              [:and (constantly true) [:not (constantly false)]]
                                                              (constantly false)]
                                                             [:not (constantly false)]]
                                                     :handler (fn [{:keys [data]}]
                                                                (+ (:left data) (:right data)))}}}]]
                  :pipes {[:foo/bar :foo.bar/query] (fn [request]
                                                      (let [command-res (get-in request [:command :response])]
                                                        (assoc-in request [:data :right] command-res)))}}
        compiled (core/compile api {:schema-registry schema-registry})
        res (core/execute compiled {} {:command [:foo/bar {}]
                                       :queries {:some-alias [:foo.bar/query {:left 1}]}})]
    (is (= {:command [:foo/bar 2], :queries {:some-alias [:foo.bar/query 3]}} res))))

(defn assigned-to-department? [])
(defn active-charge? [])
(defn in-care-team? [])
(defn task-owner? [])
(defn task-assignable? [])
(defn needs-discharge? [])
(defn charge-rn? [])
(defn bedside-nurse? [])
(defn bedside-sitter? [])
(defn aide? [])
(defn virtual-nurse? [])
(defn virtual-sitter? [])
(defn case-manager? [])
(defn sync-lead? [])
(defn admin? [])
(defn logged-in? [])
(defn assigning-self-to-room-pool? [])
(defn call-in-room? [])

(defn handler [])

(deftest health-system
  (let [api {:context
             [:health-system
              {:rules logged-in?
               :commands {:set-availability {:input-schema :any
                                             :output-schema :any
                                             :rules virtual-nurse?
                                             :handler handler}}}
              [:room-pool
               {:commands {:assign {:input-schema :any
                                    :output-schema :any
                                    :rules [:or sync-lead?
                                            [:and virtual-nurse? assigning-self-to-room-pool?]]
                                    :handler handler}}}
               [:administration
                {:rules sync-lead?
                 :commands {:create {:input-schema :any
                                     :output-schema :any
                                     :handler handler}
                            :add-nodes {:input-schema :any
                                        :output-schema :any
                                        :handler handler}
                            :remove-nodes {:input-schema :any
                                           :output-schema :any
                                           :handler handler}
                            :deactivate {:input-schema :any
                                         :output-schema :any
                                         :handler handler}}}]]
              [:department
               {:rules charge-rn?
                :commands {:export-shift-data {:input-schema :any
                                               :output-schema :any
                                               :handler handler}
                           :export-encounter-data {:input-schema :any
                                                   :output-schema :any
                                                   :handler handler}
                           :switch-active-charge {:input-schema :any
                                                  :output-schema :any
                                                  :rules active-charge?
                                                  :handler handler}}}
               [:encounter
                {:commands {:update-discharge-plan {:input-schema :any
                                                    :output-schema :any
                                                    :rules [:or
                                                            bedside-sitter?
                                                            virtual-nurse?
                                                            case-manager?
                                                            [:and assigned-to-department?
                                                             [:or charge-rn? bedside-nurse? aide?]]]
                                                    :handler handler}
                            :set-call-in-room {:input-schema :any
                                               :output-schema :any
                                               :rules [:or
                                                       [:and charge-rn? active-charge?]
                                                       [:and in-care-team? [:or bedside-nurse? aide?]]]
                                               :handler handler}
                            :list-notes {:input-schema :any
                                         :output-schema :any
                                         :handler handler}
                            :add-note {:input-schema :any
                                       :output-schema :any
                                       :rules [:or
                                               charge-rn?
                                               [:and in-care-team? [:or bedside-nurse? aide?]]]
                                       :handler handler}
                            :call-room {:input-schema :any
                                        :output-schema :any
                                        :rules [:and call-in-room? virtual-nurse? in-care-team?]
                                        :handler handler}}}
                [:<>
                 {:rules [:and charge-rn? active-charge?]
                  :commands {:start-admission {:input-schema :any
                                               :output-schema :any
                                               :handler handler}
                             :start-transfer {:input-schema :any
                                              :output-schema :any
                                              :handler handler}
                             :start-discharge {:input-schema :any
                                               :output-schema :any
                                               :rules needs-discharge?
                                               :handler handler}
                             :schedule-shift {:input-schema :any
                                              :output-schema :any
                                              :handler handler}}}]
                [:patient
                 {:commands {:edit {:input-schema :any
                                    :output-schema :any
                                    :handler handler}
                             :change-room {:input-schema :any
                                           :output-schema :any
                                           :handler handler}}}]
                [:task
                 {:commands {:defer {:input-schema :any
                                     :output-schema :any
                                     :rules [:or
                                             [:and charge-rn? assigned-to-department?]
                                             [:and task-owner? [:or bedside-nurse? aide? virtual-nurse?]]]
                                     :handler handler}
                             :update-status {:input-schema :any
                                             :output-schema :any
                                             :rules [:and
                                                     task-assignable?
                                                     [:or
                                                      charge-rn?
                                                      [:and task-owner? [:or bedside-nurse? aide? virtual-nurse?]]]]
                                             :handler handler}
                             :add-note {:input-schema :any
                                        :output-schema :any
                                        :rules [:or
                                                [:and charge-rn? active-charge?]
                                                [:and in-care-team? [:or bedside-nurse? aide?]]]
                                        :handler handler}
                             :assign-self {:input-schema :any
                                           :output-schema :any
                                           :handler handler}}}]]]]}
        compiled (core/compile api {:schema-registry schema-registry})]
    (is true)))