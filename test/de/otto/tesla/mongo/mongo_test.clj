(ns de.otto.tesla.mongo.mongo-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as c]
            [de.otto.tesla.mongo.mongo :as mongo]
            [monger.core :as mg]
            [de.otto.tesla.util.test-utils :as u]
            [de.otto.tesla.system :as system]
            [metrics.counters :as counters])
  (:import (com.mongodb MongoException DBApiLayer)))

(defn mongo-test-system [config which-db]
  (-> (system/base-system config)
      (assoc :mongo (c/using (mongo/new-mongo which-db)
                             [:config :metering :app-status]))
      (dissoc :server)))

;; import private method.
(def clear-collection! (ns-resolve 'de.otto.tesla.mongo.mongo 'clear!))

;######################################################################################

(deftest should-construct-timer-name
  (testing "should construct correct timer name for graphite"
    (is (= (mongo/read-timer-name "a.db.name") "mongo.a.db.name.read"))))

(deftest ^:unit should-read-correct-properties
  (testing "should create host property"
    (let [which-db "foodb"
          config {:default-mongo-port 27017
                  :foodb-mongo-host   "foohost"}]
      (is (= "foohost" (mongo/property-for-db config which-db "host"))))

    (testing "should create host property"
      (let [which-db "foodb"
            config {:default-mongo-port 27017
                    :foodb-mongo-host   "foohost,blahost"}
            prop (partial mongo/property-for-db config which-db)
            hosts (mongo/parse-server-address config prop)]
        (is (= [(mg/server-address "foohost") (mg/server-address "blahost")] hosts)))))

  (testing "should create host property"
    (let [which-db "foodb"
          config {:foodb-mongo-dbname "foodb"}]
      (is (= "foodb" (mongo/property-for-db config which-db "dbname"))))))

(deftest ^:unit should-create-correct-host-property-for-multiple-host-names
  (let [conf {:default-mongo-port 27017
              :foodb-mongo-host   "foohost,blahost"}
        prop (partial mongo/property-for-db conf "foodb")
        hosts (mongo/parse-server-address conf prop)]
    (is (= [(mg/server-address "foohost") (mg/server-address "blahost")] hosts))))

(deftest ^:unit should-create-correct-timer-names
  (is (= (mongo/read-timer-name "a.db.name") "mongo.a.db.name.read")))

(deftest ^:unit should-read-correct-host-and-db-properties
  (let [config {:foodb-mongo-host   "foohost"
                :foodb-mongo-dbname "foodb"}]

    (testing "should create host property"
      (is (= "foohost" (mongo/property-for-db config "foodb" "host"))))

    (testing "should create host property"
      (is (= "foodb" (mongo/property-for-db config "foodb" "dbname"))))))



(defrecord FoodbNameLookup []
  mongo/DbNameLookup
  (dbname-lookup-fun [_] (fn [] "foodb"))
  c/Lifecycle
  (start [self] self)
  (stop [self] self))


(defn test-system-with-lookup []
  (-> (system/base-system {})
      (assoc :dbname-lookup (FoodbNameLookup.))
      (assoc :mongo (c/using (mongo/new-mongo "prod")
                             [:config :metering :app-status :dbname-lookup]))
      (dissoc :server)))

(deftest ^:unit should-use-a-provided-dbname-fn
  (testing "it uses the provided function"
    (with-redefs [mongo/new-db-connection (fn [_ _] "bar")]
      (u/with-started [started (test-system-with-lookup)]
                      (is (= ((:dbname-fun (:mongo started))) "foodb"))))))


(deftest ^:integration clearing-does-not-work-on-production-data
  (u/with-started [started (mongo-test-system {:prod-mongo-host   "localhost"
                                               :prod-mongo-dbname "valuable-production-data"}
                                              "prod")]
                  (is (thrown? IllegalArgumentException
                               (clear-collection! (:mongo started) "this-data-is-worth-its-weight-in-gold")))))

(deftest ^:integration clearing-works-on-testdata
  (u/with-started [started (mongo-test-system {:prod-mongo-host   "localhost"
                                               :prod-mongo-dbname "invaluable-test-data"}
                                              "prod")]
                  (is (= :ok
                         (clear-collection! (:mongo started) "this-test-data-is-not-worth-its-weight-in-floppy-disks")))))

(deftest ^:integration writing-and-reading-a-simple-cowboy
  (u/with-started [started (mongo-test-system {:cowboys-mongo-host   "localhost"
                                               :cowboys-mongo-dbname "test-cowboy-db"} "cowboys")]
                  (let [mongo (:mongo started)
                        collection "test-cowboys"]
                    (clear-collection! mongo collection)
                    (let [written (mongo/insert! mongo collection
                                                 {:name "Bill" :occupation "Cowboy"})
                          read (mongo/find-one-checked! mongo collection
                                                        {:_id (:_id written)})]
                      (is (= (:name read) "Bill"))))))

(deftest ^:integration finding-documents-by-array-entry
  (u/with-started [started (mongo-test-system {:pseudonym-mongo-host   "localhost"
                                               :pseudonym-mongo-dbname "test-pseudonym-db"} "pseudonym")]
                  (let [mongo (:mongo started)
                        col "test-pseudonyms"]
                    (clear-collection! (:mongo started) "test-pseudonyms")
                    (mongo/insert! mongo col {:_id               "someOtherId"
                                              :visitors          ["abc" "def"]
                                              :orderedVariations []
                                              :lastAccess        #inst "2014-10-18T22:32:06.899-00:00"
                                              :v                 "1.0"})
                    (is (= "someOtherId"
                           (:_id (mongo/find-one-checked! mongo col {:visitors "abc"})))))))

(deftest ^:unit should-not-throw-any-exception-if-authentication-fails
  (with-redefs-fn {#'mongo/authenticate-mongo (fn [_ _] (throw (MongoException. "some exception")))}
    #(u/with-started [started (mongo-test-system {:default-mongo-port 27017
                                                  :foodb-mongo-dbname "foo-db"
                                                  :foodb-mongo-host   "foohost"} "foodb")]
                     (is @(:dbs (:mongo started))
                         {"foodb" :not-connected}))))

(deftest ^:integration should-add-db-id-everything-is-fine
  (u/with-started [started (mongo-test-system {:default-mongo-port 27017
                                               :foodb-mongo-dbname "foo-db"
                                               :foodb-mongo-host   "foohost"} "foodb")]
                  (is (= (class (get @(:dbs (:mongo started)) "foo-db"))
                         DBApiLayer))))


(deftest ^:unit should-count-exceptions
  (let [number-of-exceptions (atom 100)]
    (with-redefs [counters/inc! (fn [_] (swap! number-of-exceptions inc))]
      (testing "does not increase counter if no exception"
        (with-redefs [mongo/find-one! (fn [_ _ _] {})]
          (let [_ (mongo/find-one-checked! {} "col" {})]
            (is (= 100
                   @number-of-exceptions)))))
      (testing "does increase counter if exception"
        (with-redefs [mongo/find-one! (fn [_ _ _] (throw (MongoException. "timeout")))]
          (let [_ (mongo/find-one-checked! {} "col" {})]
            (is (= 101
                   @number-of-exceptions))))))))

(deftest ^:unit test-default-options
  (testing "default values"
    (let [conf {}
          prop (partial mongo/property-for-db conf "testdb")
          options (mongo/default-options prop)]
      (is (= 31
             (options :socket-timeout)))
      (is (= 2000
             (options :connect-timeout)))
      (is (= false
             (options :socket-keep-alive)))))
  (testing "default values can be configured with properties per db"
    (let [conf {:testdb-mongo-socket-timeout "42"
                :testdb-mongo-socket-keep-alive "true"}
          prop (partial mongo/property-for-db conf "testdb")
          options (mongo/default-options prop)]
      (is (= 42
             (options :socket-timeout)))
      (is (= true
             (options :socket-keep-alive))))))
