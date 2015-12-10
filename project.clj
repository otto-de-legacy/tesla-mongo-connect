(defproject de.otto/tesla-mongo-connect "0.1.8"
            :description "Addon to https://github.com/otto-de/tesla-microservice to read and write to mongodb."
            :url "https://github.com/otto-de/tesla-mongo-connect"
            :license {:name "Apache License 2.0"
                      :url  "http://www.apache.org/license/LICENSE-2.0.html"}
            :scm {:name "git"
                  :url  "https://github.com/otto-de/tesla-mongo-connect"}
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [com.novemberain/monger "3.0.1"]]

            :plugins [[lein-embongo "0.2.2"]]

            :aliases {"test" ["do" "embongo" "test"]}
            :embongo {:port     27018
                      :version  "2.6.4"
                      :data-dir "./target/mongo-data-files"}


            :profiles {:provided {:dependencies [[de.otto/tesla-microservice "0.1.19"]]}}

            :source-paths ["src"]
            :test-paths ["test" "test-resources"])
