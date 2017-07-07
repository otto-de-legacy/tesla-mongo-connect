(ns de.otto.tesla.mongo.mongo-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as c]
            [de.otto.tesla.mongo.mongo :as mongo]
            [monger.core :as mg]
            [de.otto.tesla.util.test-utils :as u]
            [de.otto.tesla.system :as system]
            [iapetos.core :as p])
  (:import (com.mongodb MongoException DB ReadPreference MongoClientOptions)))

(defn mongo-test-system [config which-db]
  (-> (system/base-system config)
      (assoc :mongo (c/using (mongo/new-mongo which-db)
                             [:config :app-status]))
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
                             [:config :app-status :dbname-lookup]))
      (dissoc :server)))

(deftest ^:unit should-use-a-provided-dbname-fn
  (testing "it uses the provided function"
    (with-redefs [mongo/new-db-connection (fn [_ _ _ _] "bar")]
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

(deftest ^:integration counting-entries-in-a-collection
  (u/with-started [started (mongo-test-system {:cowboys-mongo-host   "localhost"
                                               :cowboys-mongo-dbname "test-cowboy-db"} "cowboys")]
                  (testing "unchecked count"
                    (let [mongo (:mongo started)
                          collection "test-cowboys"]
                      (clear-collection! mongo collection)
                      (is (= (mongo/count! mongo collection {}) 0))
                      (mongo/insert! mongo collection
                                     {:name "Bill" :occupation "Cowboy"})
                      (is (= (mongo/count! mongo collection {}) 1))
                      (is (= (mongo/count! mongo collection {:name "Bill"}) 1))
                      (is (= (mongo/count! mongo collection {:name "Eddy"}) 0))
                      ))
                  (testing "checked count"
                    (let [mongo (:mongo started)
                          collection "test-cowboys"]
                      (clear-collection! mongo collection)
                      (mongo/insert! mongo collection
                                     {:name "Bill" :occupation "Cowboy"})
                      (is (= (mongo/count-checked! mongo collection {}) 1))
                      ))
                  (testing "should catch exception from mongo"
                    (let [mongo (:mongo started)
                          collection "test-cowboys"]
                      (with-redefs [mongo/count! (fn [& _] (throw (MongoException. "some exception")))]
                        (is (= nil
                               (mongo/count-checked! mongo collection {}))) ;; Look ma no exception
                        )))))

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
  (with-redefs-fn {#'mongo/create-client (fn [_ _ _] (throw (MongoException. "some exception")))}
    #(u/with-started [started (mongo-test-system {:default-mongo-port 27017
                                                  :foodb-mongo-dbname "foo-db"
                                                  :foodb-mongo-host   "foohost"} "foodb")]
                     (is @(:dbNamesToConns (:mongo started))
                         {"foodb" :not-connected}))))

(deftest ^:unit should-set-min-connections-per-host
  (testing "Setting the min connections per host"
    (let [^MongoClientOptions options (mongo/create-client-options {:min-connections-per-host 1})]
      (is (= 1 (.getMinConnectionsPerHost options)))))
  (testing "Default is zero"
    (let [^MongoClientOptions options (mongo/create-client-options {})]
      (is (= 0 (.getMinConnectionsPerHost options))))))

(deftest ^:unit should-set-max-connections-per-host
  (testing "Setting the min connections per host"
    (let [^MongoClientOptions options (mongo/create-client-options {:max-connections-per-host 1})]
      (is (= 1 (.getConnectionsPerHost options)))))
  (testing "Default is 100"
    (let [^MongoClientOptions options (mongo/create-client-options {})]
      (is (= 100 (.getConnectionsPerHost options))))))

(deftest ^:integration should-add-db-id-everything-is-fine
  (u/with-started [started (mongo-test-system {:default-mongo-port 27017
                                               :foodb-mongo-dbname "foo-db"
                                               :foodb-mongo-host   "localhost"} "foodb")]
                  (println (class (get @(:dbNamesToConns (:mongo started)) "foo-db")))
                  (is (= (class (get @(:dbNamesToConns (:mongo started)) "foo-db"))
                         DB))))

(deftest find-one-checked-test
  (with-redefs [mongo/find-one! (fn [_ _ _ fields] fields)]
    (testing "Should pass field arguments"
      (is (= ["my.nested.field"]
             (mongo/find-one-checked! {} "col" {} ["my.nested.field"]))))
    (testing "Should pass empty field arguments in case none passed in"
      (is (= []
             (mongo/find-one-checked! {} "col" {}))))))

(deftest find-checked-test
  (with-redefs [mongo/find! (fn [_ _ _ fields] fields)]
    (testing "Should pass field arguments"
      (is (= ["my.nested.field"]
             (mongo/find-checked! {} "col" {} ["my.nested.field"]))))
    (testing "Should pass empty field arguments in case none passed in"
      (is (= []
             (mongo/find-checked! {} "col" {}))))))

(deftest ^:unit should-count-exceptions
  (let [number-of-exceptions (atom 100)]
    (with-redefs [p/inc (fn [_ _] (swap! number-of-exceptions inc))]
      (testing "does not increase counter if no exception"
        (with-redefs [mongo/find-one! (fn [_ _ _ _] {})]
          (let [_ (mongo/find-one-checked! {} "col" {})]
            (is (= 100
                   @number-of-exceptions)))))
      (testing "does increase counter if exception in find-one-checked!"
        (with-redefs [mongo/find-one! (fn [_ _ _ _] (throw (MongoException. "timeout")))]
          (let [_ (mongo/find-one-checked! {} "col" {})]
            (is (= 101
                   @number-of-exceptions)))))
      (testing "does increase counter if exception in find-checked!"
        (with-redefs [mongo/find! (fn [_ _ _ _] (throw (MongoException. "timeout")))]
          (let [_ (mongo/find-checked! {} "col" {})]
            (is (= 102
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
             (options :socket-keep-alive)))
      (is (= (ReadPreference/secondaryPreferred)
             (options :read-preference)))))

  (testing "default values can be configured with properties per db"
    (let [conf {:testdb-mongo-socket-timeout    "42"
                :testdb-mongo-socket-keep-alive "true"}
          prop (partial mongo/property-for-db conf "testdb")
          options (mongo/default-options prop)]
      (is (= 42
             (options :socket-timeout)))
      (is (= true
             (options :socket-keep-alive)))))

  (testing "should choose read preference from config"
    (let [conf {:testdb-mongo-socket-timeout    "42"
                :testdb-mongo-socket-keep-alive "true"
                :testdb-mongo-read-preference   :primary-preferred}
          prop (partial mongo/property-for-db conf "testdb")
          options (mongo/default-options prop)]
      (is (= (ReadPreference/primaryPreferred)
             (options :read-preference))))))
