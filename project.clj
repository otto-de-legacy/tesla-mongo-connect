(defproject de.otto/tesla-mongo-connect "0.1.3"
            :description "Addon to https://github.com/otto-de/tesla-microservice to read and write to mongodb."
            :url "https://github.com/otto-de/tesla-mongo-connect"
            :license {:name "Apache License 2.0"
                      :url  "http://www.apache.org/license/LICENSE-2.0.html"}
            :scm {:name "git"
                  :url  "https://github.com/otto-de/tesla-mongo-connect"}
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [de.otto/tesla-microservice "0.1.12"]
                           [de.otto/tesla-zookeeper-observer "0.1.1"]
                           [com.novemberain/monger "2.1.0"]]

            :plugins [[lein-embongo "0.2.2"][lein-environ "1.0.0"]]

            :aliases {"test" ["do" "embongo" "test"]}
            :embongo {:port     27018
                      :version  "2.6.4"
                      :data-dir "./target/mongo-data-files"}
            :profiles {:test {:env {:default-mongo-port "27018"}}}
            :main ^:skip-aot de.otto.tesla.mongo.example.example-system
            :source-paths ["src" "example/src"]
            :test-paths ["test" "test-resources" "example/test"])
