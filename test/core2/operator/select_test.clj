(ns core2.operator.select-test
  (:require [clojure.test :as t]
            [core2.operator.select :as select]
            [core2.test-util :as tu]
            [core2.types :as ty])
  (:import core2.operator.select.IRelationSelector
           org.apache.arrow.vector.types.pojo.Schema
           org.apache.arrow.vector.types.Types$MinorType
           org.roaringbitmap.RoaringBitmap))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-select
  (let [a-field (ty/->field "a" (.getType Types$MinorType/BIGINT) false)
        b-field (ty/->field "b" (.getType Types$MinorType/BIGINT) false)]
    (with-open [cursor (tu/->cursor (Schema. [a-field b-field])
                                    [[{:a 12, :b 10}
                                      {:a 0, :b 15}]
                                     [{:a 100, :b 83}]
                                     [{:a 83, :b 100}]])
                select-cursor (select/->select-cursor cursor
                                                      (reify IRelationSelector
                                                        (select [_ in-rel]
                                                          (let [idxs (RoaringBitmap.)
                                                                a-col (.readColumn in-rel "a")
                                                                b-col (.readColumn in-rel "b")]
                                                            (dotimes [idx (.rowCount in-rel)]
                                                              (when (> (.getLong a-col idx)
                                                                       (.getLong b-col idx))
                                                                (.add idxs idx)))

                                                            idxs))))]
      (t/is (= [[{:a 12, :b 10}]
                [{:a 100, :b 83}]]
               (tu/<-cursor select-cursor))))))