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

(deftest ast
  (let [api      {:context [:foo {:commands {:bar {:input-schema :any
                                                   :output-schema :any
                                                   :handler (fn [_] 2)}}}
                            [:bar {:queries {:query {:input-schema :any
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
        compiled (core/compile api {:schema-registry schema-registry})]
    (println (core/execute compiled {} {:command [:foo/bar {}]
                                        :queries {:some-alias [:foo.bar/query {:left 1}]}}))
    (is (= 1 2))))