(defproject core2 "0.1.0-SNAPSHOT"
  :description "Core2 Initiative"
  :url "https://github.com/juxt/crux-rnd"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :repositories {"jcenter" {:url "https://jcenter.bintray.com"}}

  :dependencies [[org.clojure/clojure "1.10.2"]
                 [org.clojure/tools.logging "0.6.0"]
                 [org.clojure/spec.alpha "0.2.194"]
                 [org.apache.arrow/arrow-vector "3.0.0"]
                 [org.apache.arrow/arrow-memory-netty "3.0.0"]
                 [org.roaringbitmap/RoaringBitmap "0.9.8"]]

  :profiles {:uberjar {:dependencies [[ch.qos.logback/logback-classic "1.2.3"]]}
             :dev {:dependencies [[ch.qos.logback/logback-classic "1.2.3"]
                                  [org.clojure/test.check "0.10.0"]
                                  [io.airlift.tpch/tpch "0.10"]
                                  [org.openjdk.jmh/jmh-core "1.27"]
                                  [org.openjdk.jmh/jmh-generator-annprocess "1.27"]
                                  [org.clojure/data.csv "1.0.0"]
                                  [cheshire "5.10.0"]]
                   :java-source-paths ["src" "jmh"]
                   :resource-paths ["test-resources" "data"]
                   :test-selectors {:default (complement :integration)
                                    :integration :integration}}}

  :aliases {"jmh" ["trampoline" "run"
                   "-m" "org.openjdk.jmh.Main"
                   "-f" "1"
                   "-rf" "json"
                   "-rff" "target/jmh-result.json"]}

  :main core2.core

  :java-source-paths ["src"]

  :jvm-opts ["-Xmx2G"
             "-XX:MaxDirectMemorySize=2G"
             "-Dio.netty.tryReflectionSetAccessible=true"
             "-Darrow.enable_null_check_for_get=false"
             #_"-Darrow.enable_unsafe_memory_access=true"]

  :global-vars {*warn-on-reflection* true})
