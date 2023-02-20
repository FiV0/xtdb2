(ns core2.indexer.log-indexer
  (:require [core2.api :as c2]
            [core2.blocks :as blocks]
            core2.buffer-pool
            [core2.util :as util]
            [core2.vector.writer :as vw]
            [juxt.clojars-mirrors.integrant.core :as ig])
  (:import core2.buffer_pool.IBufferPool
           core2.ICursor
           core2.object_store.ObjectStore
           (core2.vector IVectorWriter)
           (java.io Closeable)
           (java.util.function Consumer)
           (org.apache.arrow.memory ArrowBuf BufferAllocator)
           (org.apache.arrow.vector BigIntVector BitVector TimeStampMicroTZVector VectorLoader VectorSchemaRoot VectorUnloader)
           (org.apache.arrow.vector.complex ListVector StructVector)) )

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface ILogOpIndexer
  (^void logPut [^long iid, ^long rowId, ^long app-timeStart, ^long app-timeEnd])
  (^void logDelete [^long iid, ^long app-timeStart, ^long app-timeEnd])
  (^void logEvict [^long iid])
  (^void commit [])
  (^void abort []))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface ILogIndexer
  (^core2.indexer.log_indexer.ILogOpIndexer startTx [^core2.api.TransactionInstant txKey])
  (^java.util.concurrent.CompletableFuture finishChunk [^long chunkIdx])
  (^void clear [])
  (^void close []))

(def ^:private log-ops-col-type
  '[:union #{:null
             [:list
              [:struct {iid :i64
                        row-id [:union #{:null :i64}]
                        application-time-start [:union #{:null [:timestamp-tz :micro "UTC"]}]
                        application-time-end [:union #{:null [:timestamp-tz :micro "UTC"]}]
                        evict? :bool}]]}])

(defn- ->log-obj-key [chunk-idx]
  (format "chunk-%s/log.arrow" (util/->lex-hex-string chunk-idx)))

(defmethod ig/prep-key :core2.indexer/log-indexer [_ opts]
  (merge {:allocator (ig/ref :core2/allocator)
          :object-store (ig/ref :core2/object-store)
          :row-counts (ig/ref :core2/row-counts)}
         opts))

(defmethod ig/init-key :core2.indexer/log-indexer [_ {:keys [^BufferAllocator allocator, ^ObjectStore object-store],
                                                      {:keys [^long max-rows-per-block]} :row-counts}]
  (let [log-writer (vw/->rel-writer allocator)
        transient-log-writer (vw/->rel-writer allocator)

        ;; we're ignoring the writers for tx-id and sys-time, because they're simple primitive vecs and we're only writing to idx 0
        ^BigIntVector tx-id-vec (-> (.writerForName transient-log-writer "tx-id" :i64)
                                    (.getVector))

        ^TimeStampMicroTZVector sys-time-vec (-> (.writerForName transient-log-writer "system-time" [:timestamp-tz :micro "UTC"])
                                                 (.getVector))

        ops-writer (.asList (.writerForName transient-log-writer "ops" log-ops-col-type))
        ^ListVector ops-vec (.getVector ops-writer)
        ops-data-writer (.asStruct (.getDataWriter ops-writer))

        row-id-writer (.writerForName ops-data-writer "row-id")
        ^BigIntVector row-id-vec (.getVector row-id-writer)
        iid-writer (.writerForName ops-data-writer "iid")
        ^BigIntVector iid-vec (.getVector iid-writer)

        app-time-start-writer (.writerForName ops-data-writer "application-time-start")
        ^TimeStampMicroTZVector app-time-start-vec (.getVector app-time-start-writer)
        app-time-end-writer (.writerForName ops-data-writer "application-time-end")
        ^TimeStampMicroTZVector app-time-end-vec (.getVector app-time-end-writer)

        evict-writer (.writerForName ops-data-writer "evict?")
        ^BitVector evict-vec (.getVector evict-writer)]

    (reify ILogIndexer
      (startTx [_ tx-key]
        (.startValue ops-writer)
        (doto tx-id-vec
          (.setSafe 0 (.tx-id tx-key))
          (.setValueCount 1))
        (doto sys-time-vec
          (.setSafe 0 (util/instant->micros (.sys-time tx-key)))
          (.setValueCount 1))

        (reify ILogOpIndexer
          (logPut [_ iid row-id app-time-start app-time-end]
            (let [op-idx (.startValue ops-data-writer)]
              (.setSafe row-id-vec op-idx row-id)
              (.setSafe iid-vec op-idx iid)
              (.setSafe app-time-start-vec op-idx app-time-start)
              (.setSafe app-time-end-vec op-idx app-time-end)
              (.setSafe evict-vec op-idx 0)

              (.endValue ops-data-writer)))

          (logDelete [_ iid app-time-start app-time-end]
            (let [op-idx (.startValue ops-data-writer)]
              (.setSafe iid-vec op-idx iid)
              (.setNull row-id-vec op-idx)
              (.setSafe app-time-start-vec op-idx app-time-start)
              (.setSafe app-time-end-vec op-idx app-time-end)
              (.setSafe evict-vec op-idx 0)

              (.endValue ops-data-writer)))

          (logEvict [_ iid]
            (let [op-idx (.startValue ops-data-writer)]
              (.setSafe iid-vec op-idx iid)
              (.setNull row-id-vec op-idx)
              (.setNull app-time-start-vec op-idx)
              (.setNull app-time-end-vec op-idx)
              (.setSafe evict-vec op-idx 1))

            (.endValue ops-data-writer))

          (commit [_]
            (.endValue ops-writer)
            (.setValueCount ops-vec 1)
            (vw/append-rel log-writer (vw/rel-writer->reader transient-log-writer))

            (.clear transient-log-writer))

          (abort [_]
            (.clear ops-vec)
            (.setNull ops-vec 0)
            (.setValueCount ops-vec 1)
            (vw/append-rel log-writer (vw/rel-writer->reader transient-log-writer))

            (.clear transient-log-writer))))

      (finishChunk [_ chunk-idx]
        (let [log-root (let [^Iterable vecs (for [^IVectorWriter w (seq log-writer)]
                                              (.getVector w))]
                         (VectorSchemaRoot. vecs))
              log-bytes (with-open [write-root (VectorSchemaRoot/create (.getSchema log-root) allocator)]
                          (let [loader (VectorLoader. write-root)
                                row-counts (blocks/list-count-blocks (.getVector log-root "ops") max-rows-per-block)]
                            (with-open [^ICursor slices (blocks/->slices log-root row-counts)]
                              (util/build-arrow-ipc-byte-buffer write-root :file
                                (fn [write-batch!]
                                  (.forEachRemaining slices
                                                     (reify Consumer
                                                       (accept [_ sliced-root]
                                                         (with-open [arb (.getRecordBatch (VectorUnloader. sliced-root))]
                                                           (.load loader arb)
                                                           (write-batch!))))))))))]
          (.putObject object-store (->log-obj-key chunk-idx) log-bytes)))

      (clear [_]
        (.clear log-writer))

      Closeable
      (close [_]
        (.close transient-log-writer)
        (.close log-writer)))))

(defn- with-latest-log-chunk [{:keys [^ObjectStore object-store ^IBufferPool buffer-pool]} f]
  (when-let [latest-chunk-idx (some-> (last (.listObjects object-store "chunk-metadata/"))
                                      (->> (re-matches #"chunk-metadata/(\p{XDigit}+)\.arrow") second)
                                      util/<-lex-hex-string)]
    @(-> (.getBuffer buffer-pool (->log-obj-key latest-chunk-idx))
         (util/then-apply
           (fn [^ArrowBuf log-buffer]
             (assert log-buffer)

             (when log-buffer
               (f log-buffer)))))))

(defn latest-tx [deps]
  (with-latest-log-chunk deps
    (fn [log-buf]
      (util/with-last-block log-buf
        (fn [^VectorSchemaRoot log-root]
          (let [tx-count (.getRowCount log-root)
                ^BigIntVector tx-id-vec (.getVector log-root "tx-id")
                ^TimeStampMicroTZVector sys-time-vec (.getVector log-root "system-time")
                ^BigIntVector row-id-vec (-> ^ListVector (.getVector log-root "ops")
                                             ^StructVector (.getDataVector)
                                             (.getChild "row-id"))]
            {:latest-tx (c2/->TransactionInstant (.get tx-id-vec (dec tx-count))
                                                 (util/micros->instant (.get sys-time-vec (dec tx-count))))
             :latest-row-id (.get row-id-vec (dec (.getValueCount row-id-vec)))}))))))
