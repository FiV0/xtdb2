(ns core2.transit
  (:require [cognitect.transit :as transit]
            [core2.api :as c2]
            [core2.error :as err])
  (:import core2.api.TransactionInstant
           java.io.Writer
           java.time.Duration))

(defn -duration-reader [s] (Duration/parse s))

(def tj-read-handlers
  {"core2/duration" (transit/read-handler -duration-reader)
   "core2/tx-instant" (transit/read-handler c2/map->TransactionInstant)
   "core2/illegal-arg" (transit/read-handler err/-iae-reader)})

(def tj-write-handlers
  {Duration (transit/write-handler "core2/duration" str)
   TransactionInstant (transit/write-handler "core2/tx-instant" #(into {} %))
   core2.IllegalArgumentException (transit/write-handler "core2/illegal-arg" ex-data)})

(when-not (System/getenv "CORE2_DISABLE_EDN_PRINT_METHODS")
  (defmethod print-method Duration [^Duration d, ^Writer w]
    (.write w (format "#core2/duration \"%s\"" d))))