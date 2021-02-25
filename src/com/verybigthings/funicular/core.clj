(ns com.verybigthings.funicular.core
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.edn :as edn]
            [sieppari.core :as si]
            [sieppari.context :as sic]
            [fmnoise.flow :refer [fail-with]]
            [clojure.string :as str]))

(defn interceptor-map? [val]
  (and map?
    (seq (set/intersection
           (-> val keys set)
           #{:enter
             :leave
             :error}))))

(s/def :com.verybigthings.funicular.core.sieppari/enter fn?)
(s/def :com.verybigthings.funicular.core.sieppari/leave fn?)
(s/def :com.verybigthings.funicular.core.sieppari/error fn?)
(s/def :com.verybigthings.funicular.core.sieppari/interceptor
  (s/and
    interceptor-map?
    (s/keys :opt-un [:com.verybigthings.funicular.core.sieppari/enter
                     :com.verybigthings.funicular.core.sieppari/leave
                     :com.verybigthings.funicular.core.sieppari/error])))

(s/def ::schema any?)
(s/def ::input-schema any?)
(s/def ::output-schema any?)

(s/def ::handler fn?)

(s/def ::resolver
  (s/and
    map?
    (s/keys
      :opt-un [::rules
               ::interceptors]
      :req-un [::handler
               ::input-schema
               ::output-schema])))

(s/def ::rule
  (s/or
    :fn fn?
    :and (s/cat
           :op #(= % :and)
           :rules (s/+ ::rule))
    :or (s/cat
          :op #(= % :or)
          :rules (s/+ ::rule))
    :not (s/cat
           :op #(= % :not)
           :rule ::rule)))

(s/def ::rules ::rule)

(s/def ::queries
  (s/map-of simple-keyword? ::resolver))

(s/def ::commands
  (s/map-of simple-keyword? ::resolver))

(s/def ::interceptors
  (s/coll-of :com.verybigthings.funicular.core.sieppari/interceptor :distinct true :into []))

(s/def ::context-props
  (s/and
    map?
    (s/keys
      :opt-un [::interceptors
               ::schema
               ::rules
               ::queries
               ::commands])))

(s/def ::context-name
  (s/and
    simple-keyword?
    (s/or
      :anon #{:<>}
      :named any?)))

(s/def ::context
  (s/and
    vector?
    (s/cat
      :name ::context-name
      :props (s/? ::context-props)
      :subcontexts (s/* ::context))))

(s/def ::pipe-connection
  (s/tuple keyword? keyword?))

(s/def ::pipes
  (s/map-of ::pipe-connection fn?))

(s/def ::api
  (s/and
    map?
    (s/keys
      :req-un [::context]
      :opt-un [::pipes])))

(s/def :com.verybigthings.funicular.request/query
  (s/tuple keyword? any?))

(s/def :com.verybigthings.funicular.request/queries
  (s/map-of
    keyword? :com.verybigthings.funicular.request/query))

(s/def :com.verybigthings.funicular.request/command
  (s/tuple keyword? any?))

(s/def :com.verybigthings.funicular/request
  (s/and
    map?
    (s/keys
      :opt-un [:com.verybigthings.funicular.request/command
               :com.verybigthings.funicular.request/queries])))

(defmulti enforce-rule (fn [_ [op & _]] op))

(defmethod enforce-rule :fn [request [_ rule-fn]]
  (boolean (rule-fn request)))

(defmethod enforce-rule :not [request [_ {:keys [rule]}]]
  (not (enforce-rule request rule)))

(defmethod enforce-rule :and [request [_ {:keys [rules]}]]
  (loop [rules rules]
    (if (seq rules)
      (let [rule (first rules)
            res (enforce-rule request rule)]
        (if res
          (recur (rest rules))
          false))
      true)))

(defmethod enforce-rule :or [request [_ {:keys [rules]}]]
  (loop [rules rules]
    (if (seq rules)
      (let [rule (first rules)
            res (enforce-rule request rule)]
        (if res
          true
          (recur (rest rules))))
      false)))


(defn log [arg]
  (clojure.pprint/pprint arg)
  arg)

(defn with-context-name [acc {[context-type context-name] :name}]
  (if (= :named context-type)
    (update acc :path conj context-name)
    acc))

(defn with-interceptors [acc {:keys [interceptors]}]
  (update acc :interceptors #(-> (concat % interceptors) vec)))

(defn make-schema-interceptor [schema {:keys [schema-registry]}]
  {:enter (fn [ctx]
            (let [data (get-in ctx [:request :data])]
              (if (m/validate schema data {:registry schema-registry})
                ctx
                (let [explained (m/explain schema data {:registry schema-registry})]
                  ;; TODO: Consider anomaly system e.g. https://github.com/cognitect-labs/anomalies
                  (sic/terminate ctx (fail-with {:msg "Invalid data"
                                                 :data {:error ::invalid-schema
                                                        :errors (:errors explained)}}))))))})

(defn with-schema [acc {:keys [schema]} opts]
  (if schema
    (-> acc
      (update :schemas conj schema)
      (update :interceptors conj (make-schema-interceptor schema opts)))
    acc))

(defn make-namespaced-resolver-name [path resolver-name]
  (if (seq path)
    (let [resolver-ns (str/join "." (map name path))]
      (keyword resolver-ns (name resolver-name)))
    resolver-name))

(defn with-rules [acc {:keys [rules]} opts]
  (let [interceptor (fn [{:keys [request] :as ctx}]
                      (if rules
                        (if (enforce-rule request rules)
                          ctx
                          (sic/terminate ctx (fail-with {:msg "Forbidden"
                                                         :data {:error ::forbidden}})))
                        ctx))]
    (update acc :interceptors conj {:enter interceptor})))

(defn with-resolvers [acc resolver-type props opts]
  (reduce-kv
    (fn [acc' resolver-name resolver]
      (let [{:keys [interceptors schemas]} (-> acc
                                             (with-interceptors resolver)
                                             (with-schema resolver opts)
                                             (with-rules resolver opts))

            namespaced-resolver-name (make-namespaced-resolver-name (:path acc) resolver-name)
            chain (conj interceptors (:handler resolver))]
        (when (get-in acc' [:resolvers namespaced-resolver-name])
          (throw (ex-info "Duplicate resolver" {:error ::duplicate-resolver
                                                :resolver namespaced-resolver-name})))
        (assoc-in acc' [:resolvers namespaced-resolver-name] {:chain chain
                                                              :schemas schemas
                                                              :path (:path acc)
                                                              :name resolver-name
                                                              :ns-name namespaced-resolver-name
                                                              :type ({:commands :command :queries :query} resolver-type)})))
    acc
    (get props resolver-type)))

(declare compile-context)

(defn with-subcontexts [acc {:keys [subcontexts]} opts]
  (reduce
    (fn [acc' subcontext]
      (compile-context acc' subcontext opts))
    acc
    subcontexts))

(defn compile-context
  ([context opts] (compile-context {:path [] :interceptors [] :schemas []} context opts))
  ([acc {:keys [props] :as context} opts]
   (-> acc
     (with-context-name context)
     (with-interceptors props)
     (with-schema props opts)
     (with-rules props opts)
     (with-resolvers :commands props opts)
     (with-resolvers :queries props opts)
     (with-subcontexts context opts))))

(defn compile-pipes [pipes resolvers opts]
  (reduce-kv
    (fn [acc source->target pipe]
      (let [[source target] source->target]
        (when (not= :command (get-in resolvers [source :type]))
          (throw (ex-info "Non existent command" {:command source
                                                  :error :non-existent-command})))
        (when (not= :query (get-in resolvers [target :type]))
          (throw (ex-info "Non existent query" {:query target
                                                :error :non-existent-query})))
        (assoc acc source->target {:enter (fn [ctx]
                                            (let [request (:request ctx)]
                                              (assoc ctx :request (pipe request))))})))
    {}
    pipes))

(defn compile [api opts]
  (s/assert ::api api)
  (let [conformed-api (s/conform ::api api)
        context (:context conformed-api)
        {:keys [resolvers]} (compile-context context opts)]
    {:resolvers resolvers
     :pipes (compile-pipes (:pipes api) resolvers opts)}))

(defn make-missing-command-error [command-name command-data]
  {:command command-name
   :data command-data
   :error :non-existent-command})

(defn execute-command [acc compiled context {:keys [command]}]
  (if command
    (let [[command-name command-data] command
          chain (get-in compiled [:resolvers command-name :chain])
          res (if chain
                (si/execute chain (assoc context :data command-data))
                (make-missing-command-error command-name command-data))]
      (assoc acc :command [command-name res]))
    acc))

(defn make-missing-query-error [query-name query-data]
  {:query query-name
   :data query-data
   :error :non-existent-query})

(defn execute-queries [{:keys [command] :as acc} compiled context {:keys [queries]}]
  (let [[command-name command-res] command]
    (reduce-kv
      (fn [acc' query-alias [query-name query-data]]
        (let [chain (get-in compiled [:resolvers query-name :chain])]
          (if chain
            (let [pipe (get-in compiled [:pipes [command-name query-name]])
                  context' (if command
                             (assoc context :command {:name command-name :response command-res})
                             context)
                  chain' (if pipe (into [pipe] chain) chain)
                  res (si/execute chain' (assoc context' :data query-data))]
              (assoc-in acc' [:queries query-alias] [query-name res]))
            (assoc-in acc' [:queries query-alias] [query-name (make-missing-query-error query-name query-data)]))))
      acc
      queries)))

(defn execute [compiled context request]
  (s/assert :com.verybigthings.funicular/request request)
  (-> {}
    (execute-command compiled context request)
    (execute-queries compiled context request)))