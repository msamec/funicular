(ns com.verybigthings.funicular.anomalies)

(defn anomaly
  ([category] (anomaly category nil {}))
  ([category message] (anomaly category message {}))
  ([category message payload]
   (cond-> payload
     true (assoc :funicular.anomaly/category category)
     message (assoc :funicular.anomaly/message message))))

(def unavailable (partial anomaly :funicular.anomaly.category/unavailable))
(def interrupted (partial anomaly :funicular.anomaly.category/interrupted))
(def incorrect (partial anomaly :funicular.anomaly.category/incorrect))
(def forbidden (partial anomaly :funicular.anomaly.category/forbidden))
(def unsupported (partial anomaly :funicular.anomaly.category/unsupported))
(def not-found (partial anomaly :funicular.anomaly.category/not-found))
(def conflict (partial anomaly :funicular.anomaly.category/conflict))
(def fault (partial anomaly :funicular.anomaly.category/fault))
(def busy (partial anomaly :funicular.anomaly.category/busy))

(def internal-error (partial anomaly :funicular.anomaly.category/internal-error))

(defn ->ex-info
  ([anomaly] (->ex-info anomaly nil))
  ([anomaly cause]
   (ex-info (:funicular.anomaly/message anomaly) anomaly cause)))