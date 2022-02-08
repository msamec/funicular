(ns com.verybigthings.funicular.transit
  "Connect time-literals to transit."
  (:require [time-literals.read-write]
            [cognitect.transit :as transit]
            #?(:cljs [com.cognitect.transit.types :as ty])
            #?(:cljs [java.time :refer [Period
                                        LocalDate
                                        LocalDateTime
                                        ZonedDateTime
                                        OffsetTime
                                        Instant
                                        OffsetDateTime
                                        ZoneId
                                        DayOfWeek
                                        LocalTime
                                        Month
                                        Duration
                                        Year
                                        YearMonth]]))
  #?(:clj (:import (java.io ByteArrayOutputStream ByteArrayInputStream)
                   (java.time Period
                              LocalDate
                              LocalDateTime
                              ZonedDateTime
                              OffsetTime
                              Instant
                              OffsetDateTime
                              ZoneId
                              DayOfWeek
                              LocalTime
                              Month
                              Duration
                              Year
                              YearMonth))))
#?(:cljs
   (extend-type ty/UUID
     IUUID))

(def time-classes
  {'period Period
   'date LocalDate
   'date-time LocalDateTime
   'zoned-date-time ZonedDateTime
   'offset-time OffsetTime
   'instant Instant
   'offset-date-time OffsetDateTime
   'time LocalTime
   'duration Duration
   'year Year
   'year-month YearMonth
   'zone ZoneId
   'day-of-week DayOfWeek
   'month Month})

(def write-handlers
  {:handlers
   (into {}
         (for [[tick-class host-class] time-classes]
           [host-class (transit/write-handler (constantly (name tick-class)) str)]))})

(def read-handlers
  {:handlers
   (into {} (for [[sym fun] time-literals.read-write/tags]
              [(name sym) (transit/read-handler fun)]))}) ; omit "time/" for brevity

(defn ->transit "Encode data structure to transit."
  [arg]
  #?(:clj (let [out (ByteArrayOutputStream.)
                writer (transit/writer out :json write-handlers)]
            (transit/write writer arg)
            (.toString out))
     :cljs (transit/write (transit/writer :json write-handlers) arg)))

(defn <-transit "Decode data structure from transit."
  [json]
  #?(:clj (let [in (ByteArrayInputStream. (.getBytes json))
                reader (transit/reader in :json read-handlers)]
            (transit/read reader))
     :cljs (transit/read (transit/reader :json read-handlers) json)))