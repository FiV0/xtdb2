(ns core2.operator.table
  (:require [core2.error :as err]
            [core2.types :as ty]
            [core2.util :as util]
            [core2.vector.writer :as vw]
            [core2.vector.indirect :as iv])
  (:import core2.ICursor
           [java.util ArrayList LinkedList List]
           org.apache.arrow.memory.BufferAllocator
           org.apache.arrow.vector.complex.DenseUnionVector))

(set! *unchecked-math* :warn-on-boxed)

(deftype TableCursor [^BufferAllocator allocator
                      ^List rows
                      ^:unsynchronized-mutable done?]
  ICursor
  (getColumnNames [_]
    (into #{} (map name) (keys (first rows))))

  (tryAdvance [this c]
    (if (or done? (.isEmpty rows))
      false
      (do
        (set! (.done? this) true)

        (let [out-cols (LinkedList.)]
          (try
            (doseq [k (keys (first rows))]
              (let [out-vec (DenseUnionVector/empty (name k) allocator)
                    out-writer (.asDenseUnion (vw/vec->writer out-vec))]
                (.add out-cols (iv/->direct-vec out-vec))
                (dorun
                 (map-indexed (fn [idx row]
                                (util/set-value-count out-vec idx)

                                (.startValue out-writer)
                                (let [v (get row k)]
                                  (doto (.writerForType out-writer (ty/value->leg-type v))
                                    (.startValue)
                                    (->> (ty/write-value! v))
                                    (.endValue)))
                                (.endValue out-writer))

                              rows))))
            (catch Exception e
              (run! util/try-close out-cols)
              (throw e)))

          (with-open [out-rel (iv/->indirect-rel out-cols)]
            (.accept c out-rel)
            true)))))

  (close [_]))

(defn ->table-cursor ^core2.ICursor [^BufferAllocator allocator, ^List rows]
  (when-not (or (empty? rows) (= 1 (count (distinct (map keys rows)))))
    (throw (err/illegal-arg :mismatched-keys-in-table
                            {::err/message "Mismatched keys in table"
                             :key-sets (into #{} (map keys) rows)})))

  (TableCursor. allocator (ArrayList. rows) false))