(defproject de.otto/tesla-mongo-connect "0.3.1"
            :description "Addon to https://github.com/otto-de/tesla-microservice to read and write to mongodb."
            :url "https://github.com/otto-de/tesla-mongo-connect"
            :license {:name "Apache License 2.0"
                      :url  "http://www.apache.org/license/LICENSE-2.0.html"}
            :scm {:name "git"
                  :url  "https://github.com/otto-de/tesla-mongo-connect"}
            :dependencies [[org.clojure/clojure "1.8.0"]
                           [com.novemberain/monger "3.1.0"]
                           [de.otto/goo "0.2.4"]]

            :plugins [[lein-embongo "0.2.2"]]

            :aliases {"test" ["do" "embongo" "test"]}
            :embongo {:port     27018
                      :version  "2.6.4"
                      :data-dir "./target/mongo-data-files"}

	    :lein-release {:deploy-via :clojars}
            :profiles {:provided {:dependencies [[de.otto/tesla-microservice "0.11.4"]]}
		       :dev {:plugins [[lein-release/lein-release "1.0.9"]]}}

            :source-paths ["src"]
            :test-paths ["test" "test-resources"])
