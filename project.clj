(System/setProperty "de.flapdoodle.embed.io.tmpdir" ".")

(defproject de.otto/tesla-mongo-connect "0.1.3"
            :description "Addon to https://github.com/otto-de/tesla-microservice to read and write to mongodb."
            :url "https://github.com/otto-de/tesla-mongo-connect"
            :license {:name "Apache License 2.0"
                      :url  "http://www.apache.org/license/LICENSE-2.0.html"}
            :scm {:name "git"
                  :url  "https://github.com/otto-de/tesla-mongo-connect"}
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [com.stuartsierra/component "0.2.2"]
                           [de.otto/tesla-microservice "0.1.10"]
                           [de.otto/tesla-zookeeper-observer "0.1.0"]
                           [com.novemberain/monger "2.0.0"]
                           [metrics-clojure "2.3.0"]

                           ;; logging
                           [org.clojure/tools.logging "0.3.0"]
                           [org.slf4j/slf4j-api "1.7.7"]
                           [ch.qos.logback/logback-core "1.1.2"]
                           [ch.qos.logback/logback-classic "1.1.2"]
                           [net.logstash.logback/logstash-logback-encoder "3.4"]]

            :plugins [[lein-embongo "0.2.1"]]

            :aliases {"test" ["do" "embongo" "test"]}
            :embongo {:port     27018
                      :version  "2.6.4"
                      :data-dir "/tmp/mongo-data-files"}
            :profiles {:test {:env {:default-mongo-port "27018"}}}
            :main ^:skip-aot de.otto.tesla.mongo.example.example-system
            :source-paths ["src" "example/src"]
            :test-paths ["test" "test-resources" "example/test"]
            )
