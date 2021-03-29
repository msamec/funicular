(ns com.verybigthings.funicular.controller
  (:require [keechma.next.controller :as ctrl]
            [lambdaisland.fetch :as fetch]
            [keechma.next.protocols :as keechma-pt]
            [promesa.core :as p]
            [cljs.core.async :refer [chan timeout <! close! alts! put!]]
            [tick.alpha.api :as t]
            [com.verybigthings.funicular.transit :as funicular-transit]
            [cognitect.transit :as transit]
            [keechma.pipelines.core :refer [in-pipeline?] :refer-macros [pipeline!]]
            [keechma.pipelines.runtime :refer [pipeline?]]
            [goog.object :as gobj])
  (:require-macros [cljs.core.async.macros :refer [go-loop go]]))

(defn hijack-command-deferred-then-and-catch [ctrl request-payload d]
  (let [{[command-name _] :command} request-payload
        state* (volatile! {:committed? false
                           :on-fulfilled #{}
                           :on-rejected #{}})
        -then (gobj/get d "then")
        -catch (gobj/get d "catch")
        wrap-on-fulfilled (fn [on-fulfilled]
                            (let [on-fulfilled (if (fn? on-fulfilled) on-fulfilled (constantly on-fulfilled))]
                              (vswap! state* update :on-fulfilled conj on-fulfilled)
                              (fn [payload]
                                (let [state @state*]
                                  (if (:committed? state)
                                    (get-in state [:fulfilled on-fulfilled])
                                    (let [{:keys [command]} payload]
                                      (ctrl/transact
                                        ctrl
                                        (fn []
                                          (let [fulfilled (reduce
                                                            (fn [m f] (assoc m f (f payload)))
                                                            {}
                                                            (:on-fulfilled state))]
                                            (vswap! state* assoc :committed? true :fulfilled fulfilled)
                                            (ctrl/broadcast ctrl [:funicular/after command-name] {:command command})
                                            (get fulfilled on-fulfilled))))))))))
        wrap-on-rejected (fn [on-rejected]
                           (let [on-rejected (if (fn? on-rejected) on-rejected (constantly on-rejected))]
                             (vswap! state* update :on-rejected conj on-rejected)
                             (fn [payload]
                               (let [state @state*]
                                 (if (:committed? state)
                                   (get-in state [:rejected on-rejected])
                                   (ctrl/transact
                                     ctrl
                                     (fn []
                                       (let [rejected (reduce
                                                        (fn [m f] (assoc m f (f payload)))
                                                        {}
                                                        (:on-rejected state))]
                                         (vswap! state* assoc :committed? true :rejected rejected)
                                         (ctrl/broadcast ctrl [:funicular/error command-name] payload)
                                         (get rejected on-rejected)))))))))
        wrapped-then (fn then
                       ([on-fulfilled] (then on-fulfilled nil))
                       ([on-fulfilled on-rejected]
                        (let [wrapped-on-fulfilled (wrap-on-fulfilled on-fulfilled)
                              wrapped-on-rejected  (when on-rejected (wrap-on-rejected on-rejected))]
                          (.call -then d wrapped-on-fulfilled wrapped-on-rejected))))
        wrapped-catch (fn [on-rejected]
                        (let [wrapped-on-rejected  (when on-rejected (wrap-on-rejected on-rejected))]
                          (.call -catch wrapped-on-rejected)))]
    (gobj/set d "then" wrapped-then)
    (gobj/set d "catch" wrapped-catch)
    d))

(def transit-json-reader
  (transit/reader :json funicular-transit/read-handlers))
(def transit-json-writer
  (transit/writer :json funicular-transit/write-handlers))

(def fetch-opts {:transit-json-reader transit-json-reader
                 :transit-json-writer transit-json-writer})

(derive ::controller :keechma/controller)

(defprotocol IQueryRequester
  (queue-request [this payload deferred])
  (terminate [this]))

(defprotocol IApi
  (-req! [this payload]))

(defprotocol IQueryAttacher
  (-get-command [this])
  (attach! [this payload]))

(def default-config
  {:keechma.controller/params true
   :keechma.controller/is-global true
   :funicular/url "/api"})

(defn extract-result [res]
  (:body res))

(defn request-queries! [{:funicular/keys [url]} {:keys [queries deferred->ids id->aliases]}]
  (->> (fetch/post url (assoc fetch-opts :body {:queries queries}))
    (p/map extract-result)
    (p/map (fn [{:keys [queries]}]
             (doseq [[deferred ids] deferred->ids]
               (let [deferred-id->aliases (get id->aliases deferred)
                     deferred-res (reduce
                                    (fn [acc id]
                                      (let [r (get queries id)
                                            aliases (get deferred-id->aliases id)]
                                        (reduce #(assoc %1 %2 r) acc aliases)))
                                    {}
                                    ids)]
                 (p/resolve! deferred {:queries deferred-res})))))
    (p/catch (fn [err]
               (doseq [deferred (vals deferred->ids)]
                 (p/reject! deferred err))))))

(defn deferred-result-handler [{:keys [command queries] :as res}]
  (let [[_ command-result] command
        errored-query-result
        (reduce-kv
          (fn [_ _ [_ query-result]]
            (when (contains? query-result :funicular.anomaly/category)
              (reduced query-result)))
          nil
          queries)]
    (cond
      (contains? command-result :funicular.anomaly/category)
      (p/rejected (ex-info (:funicular.anomaly/message command-result) command-result))

      errored-query-result
      (p/rejected (ex-info (:funicular.anomaly/message errored-query-result) errored-query-result))

      :else res)))

(defn deferred-with-result-handler [deferred]
  (->> deferred
    (p/map deferred-result-handler)))

(def set-conj (fnil conj #{}))

(defn merge-queries [queued {:keys [payload deferred]}]
  (reduce-kv
    (fn [acc query-alias query]
      (let [query-id (or (get-in acc [:query->id query]) (keyword (gensym "req-")))]
        (-> acc
          (assoc-in [:query->id query] query-id)
          (update-in [:id->aliases deferred query-id] set-conj query-alias)
          (update-in [:deferred->ids deferred] set-conj query-id)
          (assoc-in [:queries query-id] query))))
    queued
    (:queries payload)))

(defn make-query-requester [ctrl]
  (let [in-chan (chan)]
    (go-loop [queued {}]
      ;; We only timeout when there are pending queries, otherwise we block on the
      ;; in-chan. 5 msec timeout is a moving window, since it will reset every time
      ;; a new request comes in. In practice this shouldn't cause any problems since
      ;; requests will come in bursts - for instance on the route change.
      (let [timeout-chan (when (seq queued) (timeout 5))
            [val port] (alts! (if (seq queued) [timeout-chan in-chan] [in-chan]))]
        (cond
          (= timeout-chan port)
          (do
            (request-queries! ctrl queued)
            (recur {}))

          (and val (= port in-chan))
          (recur (merge-queries queued val)))))
    (reify
      IQueryRequester
      (queue-request [_ payload deferred]
        (put! in-chan {:payload payload :deferred deferred}))
      (terminate [_]
        (close! in-chan)))))

(defn collect-attached-queries [query-collector-chan]
  (go-loop [queries {}]
    (let [timeout-chan (timeout 1)
          [val port] (alts! [timeout-chan query-collector-chan])]
      (cond
        (= timeout-chan port)
        queries

        val
        (recur (merge-queries queries val))))))

(defn get-reject-pipeline [command-name err]
  (pipeline! [_ ctrl]
    (ctrl/broadcast ctrl [:funicular/error command-name] err)
    (throw err)))

(defn get-resolve-pipeline [command-name {:keys [command] :as payload}]
  (let [[_ command-result] command]
    (if (contains? command-result :funicular.anomaly/category)
      (let [err (p/rejected (ex-info (:funicular.anomaly/message command-result) command-result))]
        (get-reject-pipeline command-name err))
      (pipeline! [_ ctrl]
        (ctrl/broadcast ctrl [:funicular/after command-name] {:command (:command payload)})
        payload))))

(defn request-command! [{:funicular/keys [url] :as ctrl} is-called-from-pipeline {:keys [command] :as payload} deferred]
  (let [[command-name command-payload] command
        command-has-queries (-> payload :queries seq boolean)
        query-collector-chan (chan)
        collected-queries-chan (collect-attached-queries query-collector-chan)
        query-attacher (reify IQueryAttacher
                         (-get-command [_]
                           command-payload)
                         (attach! [_ payload]
                           (when (contains? payload :command)
                             (throw (ex-info "Commands can't be attached to another command" {:payload payload})))
                           (let [deferred (p/deferred)]
                             (put! query-collector-chan {:payload payload :deferred deferred})
                             (deferred-with-result-handler deferred))))]
    (put! query-collector-chan {:payload payload :deferred deferred})
    (ctrl/broadcast ctrl [:funicular/before command-name] query-attacher)
    (go
      (let [{:keys [queries deferred->ids id->aliases] :as collected} (<! collected-queries-chan)]
        (->> (fetch/post url (assoc fetch-opts :body {:queries (or queries {}) :command command}))
          (p/map extract-result)
          (p/map (fn [{:keys [queries command]}]
                   (when-not command-has-queries
                     (if is-called-from-pipeline
                       (p/resolve! deferred (get-resolve-pipeline command-name {:command command}))
                       (p/resolve! deferred {:command command})))
                   (doseq [[d ids] deferred->ids]
                     (let [d-id->aliases (get id->aliases d)
                           d-res (reduce
                                   (fn [acc id]
                                     (let [r (get queries id)
                                           aliases (get d-id->aliases id)]
                                       (reduce #(assoc %1 %2 r) acc aliases)))
                                   {}
                                   ids)
                           payload {:queries d-res :command command}]
                       (if (and (= d deferred) is-called-from-pipeline)
                         (p/resolve! d (get-resolve-pipeline command-name payload))
                         (p/resolve! d payload))))))
          (p/catch (fn [err]
                     (doseq [d (vals deferred->ids)]
                       (if (and (= d deferred) is-called-from-pipeline)
                         (p/reject! d (get-reject-pipeline command-name err))
                         (p/resolve! d err))))))))))

(defmethod ctrl/api ::controller [{:funicular/keys [url] :as ctrl}]
  (let [query-requester (::query-requester ctrl)]
    (reify IApi
      (-req! [_ payload]
        (if (:command payload)
          (let [deferred (p/deferred)
                is-called-from-pipeline (in-pipeline?) ]
            (request-command! ctrl is-called-from-pipeline payload deferred)
            (if is-called-from-pipeline
              (deferred-with-result-handler deferred)
              (let [deferred-with-hijacked (->> deferred
                                             deferred-with-result-handler
                                             (hijack-command-deferred-then-and-catch ctrl payload))]
                ;; Force at least one on-fulfilled and on-rejected handlers so we're
                ;; sure code in `hijack-command-deferred-then-and-catch` is called
                (->> deferred-with-hijacked
                  (p/map identity)
                  (p/catch identity))
                deferred-with-hijacked)))
          (let [deferred (p/deferred)]
            (queue-request query-requester payload deferred)
            (deferred-with-result-handler deferred)))))))

(defmethod ctrl/init ::controller [ctrl]
  (let [query-requester (make-query-requester ctrl)]
    (assoc ctrl ::query-requester query-requester)))

(defmethod ctrl/terminate ::controller [{::keys [query-requester]}]
  (terminate query-requester))

(defn install
  ([app]
   (install app {}))
  ([app config]
   (assoc-in app [:keechma/controllers ::controller]
     (merge default-config config))))

(defn command
  ([command-name payload]
   (command {} command-name payload))
  ([acc command-name payload]
   (assoc acc :command [command-name payload])))

(defn query
  ([query-name payload]
   (query {} query-name payload))
  ([acc query-name payload]
   (assoc-in acc [:queries query-name] [query-name payload])))

(defn query-as
  ([query-alias query-name payload]
   (query-as {} query-alias query-name payload))
  ([acc query-alias query-name payload]
   (assoc-in acc [:queries query-alias] [query-name payload])))

(defn get-command
  ([payload] (get-command payload nil))
  ([payload default]
   (if (satisfies? IQueryAttacher payload)
     (or (-get-command payload) default)
     (let [{:keys [command]} payload
           [_ res] command]
       (or res default)))))

(defn get-query
  ([payload query-alias] (get-query payload query-alias nil))
  ([{:keys [queries]} query-alias default]
   (let [[_ res] (get queries query-alias)]
     (or res default))))

(defn req! [{:keechma/keys [app]} payload]
  (let [api* (keechma-pt/-get-api* app ::controller)]
    (if-let [api @api*]
      (-req! api payload)
      ;; Handle the case where this controller is started after the calling controller
      ;; because both might be without deps, which means that the order is nondeterministic
      (let [deferred (p/deferred)]
        (js/setTimeout #(p/resolve! deferred (-req! @api* payload)) 1)
        deferred))))

(defn query!
  ([ctrl query-name payload]
   (query! ctrl query-name payload nil))
  ([ctrl query-name payload default]
   (->> (req! ctrl (query query-name payload))
     (p/map #(get-query % query-name default)))))

(defn command!
  ([ctrl command-name payload]
   (command! ctrl command-name payload nil))
  ([ctrl command-name payload default]
   (->> (req! ctrl (command command-name payload))
     (p/map (fn [res]
              (if (pipeline? res)
                (update-in res [:pipeline :begin] conj (fn [value _] (get-command value default)))
                (get-command res default)))))))

(defn attach-query!
  ([value query-name payload]
   (attach-query! value query-name payload nil))
  ([value query-name payload default]
   (->> (attach! value (query query-name payload))
     (p/map #(get-query % query-name default)))))
