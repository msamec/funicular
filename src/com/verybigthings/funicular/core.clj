(ns com.verybigthings.funicular.core
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]
            [sieppari.core :as si]
            [sieppari.context :as sic]
            [clojure.string :as str]
            [com.verybigthings.funicular.anomalies :as anom]
            [clojure.pprint]))

(defn deep-merge-malli-errors [a b]
  (merge-with (fn [x y]
                (cond (map? y) (deep-merge-malli-errors x y)
                      (vector? y) (concat x y)
                      :else y))
    a b))

(defn interceptor-map? [val]
  (and map?
    (seq (set/intersection
           (-> val keys set)
           #{:enter
             :leave
             :error}))))

(s/def :com.verybigthings.funicular.core.interceptor/enter fn?)
(s/def :com.verybigthings.funicular.core.interceptor/leave fn?)
(s/def :com.verybigthings.funicular.core.interceptor/error fn?)
(s/def :com.verybigthings.funicular.core/interceptor
  (s/and
    interceptor-map?
    (s/keys :opt-un [:com.verybigthings.funicular.core.interceptor/enter
                     :com.verybigthings.funicular.core.interceptor/leave
                     :com.verybigthings.funicular.core.interceptor/error])))

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
  (s/coll-of :com.verybigthings.funicular.core/interceptor :distinct true :into []))

(s/def ::context-props
  (s/and
    map?
    (s/keys
      :opt-un [::interceptors
               ::input-schema
               ::rules
               ::queries
               ::commands])))

(s/def ::api-context-name
  (s/and
    simple-keyword?
    (s/or
      :anon #{:<>}
      :named any?)))

(s/def ::api
  (s/and
    vector?
    (s/cat
      :name ::api-context-name
      :props (s/? ::context-props)
      :api-subcontexts (s/* ::api))))

(s/def ::context
  map?)

(s/def ::pipe-connection
  (s/tuple keyword? keyword?))

(s/def ::pipes
  (s/map-of ::pipe-connection fn?))

(s/def ::funicular
  (s/and
    map?
    (s/keys
      :req-un [::api]
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

(def FunicularAnomaly
  [:map
   [:funicular.anomaly/category :keyword]])

;; TODO: Make this toggleable
(defn sanitize-error-keys [error]
  (reduce-kv
    (fn [acc k v]
      (let [k-ns (namespace k)]
        (if (or (= "funicular" k-ns) (str/starts-with? k-ns "funicular."))
          (assoc acc k v)
          acc)))
    {}
    error)
  error)

(def root-error-interceptor
  {:error (fn [{:keys [error] :as ctx}]
            (let [data     (ex-data error)
                  response (if (contains? data :funicular.anomaly/category)
                             data
                             (anom/internal-error (ex-message error)))]
              (-> ctx
                (dissoc :error)
                (assoc :response (sanitize-error-keys response)))))})

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

(defn with-interceptors
  "Collects all interceptors in order of doc traversal into a flat list.
   This list gets forwarded into Sieppari for execution."
  [acc {:keys [interceptors]}]
  (update acc :interceptors #(-> (concat % interceptors) vec)))

(def set-conj (fnil conj #{}))

(defn humanize
  ([acc explanation]
   (humanize acc explanation nil))
  ([acc {:keys [value errors]} {f :wrap :or {f :message} :as options}]
   (if errors
     (if (coll? value)
       (reduce
         (fn [acc error]
           (let [error-path  (me/error-path error options)
                 error-path' (if (seq error-path) error-path :funicular/errors)]
             (update acc error-path' set-conj (f (me/with-error-message error options)))))
         acc
         errors))
     (reduce
       (fn [acc error]
         (update acc :funicular/errors set-conj (f (me/with-error-message error options))))
       acc
       errors))))

(defn schemas->validator-explainer [schemas opts]
  (let [ves (mapv
              (fn [s] {:explainer (m/explainer s opts)
                       :validator (m/validator s opts)})
              schemas)]
    (fn [data]
      (let [errors (reduce
                     (fn [acc {:keys [explainer validator]}]
                       (if (validator data)
                         acc
                         (->> data
                           explainer
                           (humanize acc))))
                     {}
                     ves)]
        (->> errors
          (mapv (fn [[k v]] [k (-> v sort vec)]))
          (into {}))))))

(defn with-input-schema-interceptor
  "Given that an input schema is present on the current node,
   injects a new `:enter` interceptor that validates
   the input schema for the given context at runtime"
  [{:keys [input-schemas] :as acc} {:malli/keys [registry]}]
  (if (seq input-schemas)
    (let [malli-opts {:registry registry}
          validator-explainer (schemas->validator-explainer input-schemas malli-opts)
          interceptor
          {:enter
           (fn [ctx]
             (let [data   (get-in ctx [:request :data])
                   errors (validator-explainer data)]
               (if (empty? errors)
                 ctx
                 (let [payload {:funicular.anomaly/subcategory :funicular.anomaly.category.incorrect/input-data
                                :funicular/errors errors}]
                   (throw (anom/->ex-info (anom/incorrect "Invalid input" payload)))))))}]
      (update acc :interceptors #(into [interceptor] %)))
    acc))

(defn with-output-schema-interceptor
  "Given that an output schema is present on the current node,
   injects a new ':leave' interceptor that validates
   the output schema for the given context at runtime."
  [{:keys [output-schemas] :as acc} {:malli/keys [registry]}]
  (if (seq output-schemas)
    (let [malli-opts {:registry registry}
          funiculary-anomaly-validator (m/validator FunicularAnomaly)
          validator-explainer (schemas->validator-explainer output-schemas malli-opts)
          interceptor
          {:leave (fn [{:keys [response] :as ctx}]
                    (if (funiculary-anomaly-validator response)
                      ctx
                      (let [data   (get-in ctx [:response])
                            errors (validator-explainer data)]
                        (if (empty? errors)
                          ctx
                          (let [payload {:funicular.anomaly/subcategory :funicular.anomaly.category.incorrect/output-data
                                         :funicular/errors errors}]
                            (throw (anom/->ex-info (anom/incorrect "Invalid output" payload))))))))}]
      (update acc :interceptors conj interceptor))
    acc))

(defn with-schema
  "Appends the schemas present on the current node
   onto a list of either input or output schemas."
  [acc {:keys [input-schema output-schema]} opts]
  (cond-> acc
    input-schema
    (update :input-schemas conj input-schema)

    output-schema
    (update :output-schemas conj output-schema)))

(defn make-namespaced-resolver-name [path resolver-name]
  (if (seq path)
    (let [resolver-ns (str/join "." (map name path))]
      (keyword resolver-ns (name resolver-name)))
    resolver-name))

(defn with-rules
  "Builds a Sieppari interceptor based on the rule definition.
   At runtime, the interceptor checks if the rule is satisfied.
   If the rule fails, an early exit happens and no further
   interceptors are executed."
  [acc {:keys [rules]} opts]
  (let [interceptor (fn [{:keys [request] :as ctx}]
                      (if rules
                        (if (enforce-rule request rules)
                          ctx
                          (sic/terminate ctx (anom/forbidden)))
                        ctx))]
    (update acc :interceptors conj {:enter interceptor})))

(defn with-resolvers [acc resolver-type props opts]
  (reduce-kv
    (fn [acc' resolver-name {:keys [handler] :as resolver}]
      (let [{:keys [interceptors input-schemas output-schemas]}
            (-> acc
              (with-rules resolver opts)
              (with-interceptors resolver)
              (with-schema resolver opts)
              (with-input-schema-interceptor opts)
              (update :interceptors conj handler)
              (with-output-schema-interceptor opts))

            namespaced-resolver-name (make-namespaced-resolver-name (:path acc) resolver-name)]
        (when (get-in acc' [:resolvers namespaced-resolver-name])
          (throw (ex-info "Duplicate resolver" {:error ::duplicate-resolver
                                                :resolver namespaced-resolver-name})))
        (assoc-in acc' [:resolvers namespaced-resolver-name] {:chain (into [root-error-interceptor] interceptors)
                                                              :input-schemas input-schemas
                                                              :output-schemas output-schemas
                                                              :input-schema (last input-schemas)
                                                              :output-schema (last output-schemas)
                                                              :path (:path acc)
                                                              :name resolver-name
                                                              :ns-name namespaced-resolver-name
                                                              :type ({:commands :command :queries :query} resolver-type)})))
    acc
    (get props resolver-type)))

(declare compile-api)

(defn with-api-subcontexts [acc {:keys [api-subcontexts]} opts]
  (reduce
    (fn [acc' api-subcontext]
      (let [resolvers (:resolvers (compile-api acc' api-subcontext opts))]
        (update acc' :resolvers merge resolvers)))
    acc
    api-subcontexts))

(defn compile-api
  ([context opts] (compile-api {:path [] :interceptors [] :input-schemas [] :output-schemas []} context opts))
  ([acc {:keys [props] :as context} opts]
   (-> acc
     (with-context-name context)
     (with-rules props opts)
     (with-schema props opts)
     (with-interceptors props)
     (with-resolvers :commands props opts)
     (with-resolvers :queries props opts)
     (with-api-subcontexts context opts))))

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

(defn compile
  "Compiles the Funicular definition file into a data struture that can be executed by `execute`"
  [funicular-def opts]
  (s/assert ::funicular funicular-def)
  (let [conformed-funicular (s/conform ::funicular funicular-def)
        api (:api conformed-funicular)
        {:keys [resolvers]} (compile-api api opts)]
    {:resolvers resolvers
     :pipes (compile-pipes (:pipes funicular-def) resolvers opts)}))

(defn make-missing-command-error [command-name command-data]
  {:command command-name
   :data command-data
   :funicular.anomaly/subcategory :funicular.anomaly.category.not-found/command})

(defn execute-command [acc compiled context {:keys [command]}]
  (if command
    (let [[command-name command-data] command
          chain (get-in compiled [:resolvers command-name :chain])
          res (if chain
                (si/execute chain (assoc context :data command-data :command command-name))
                (anom/not-found "Command not found" (make-missing-command-error command-name command-data)))]
      (assoc acc :command [command-name res]))
    acc))

(defn make-missing-query-error [query-name query-data]
  {:query query-name
   :data query-data
   :funicular.anomaly/subcategory :funicular.anomaly.category.not-found/query})

;; TODO queries should return errors when command returns error
(defn execute-queries [{:keys [command] :as acc} compiled context {:keys [queries]}]
  (let [[command-name command-res] command]
    (reduce-kv
      (fn [acc' query-alias [query-name query-data]]
        (let [chain (get-in compiled [:resolvers query-name :chain])]
          (if chain
            (let [pipe (get-in compiled [:pipes [command-name query-name]])
                  context' (-> (if command
                                 (assoc context :command {:name command-name :response command-res})
                                 context)
                              (assoc :query query-name)
                              (assoc :data query-data))
                  chain' (if pipe (into [pipe] chain) chain)
                 res (si/execute chain' context')]
              (assoc-in acc' [:queries query-alias] [query-name res]))
            (assoc-in acc' [:queries query-alias] [query-name (anom/not-found "Query not found" (make-missing-query-error query-name query-data))]))))
      acc
      queries)))

(defn execute [compiled context request]
  (s/assert :com.verybigthings.funicular/request request)
  (-> {}
    (execute-command compiled context request)
    (execute-queries compiled context request)))

(defn inspect [compiled]
  (->> compiled
    :resolvers
    (map (fn [[k v]] [k (select-keys v [:input-schemas :input-schema :output-schemas :output-schema :type])]))
    (sort-by (fn [[k _]] (str k)))
    vec))