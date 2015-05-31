# tesla-mongo-connect

An addon to [tesla-microservice](https://github.com/otto-de/tesla-microservice)
that allows to read an write to mongodb. Most accesses are metered in graphite.

[![Build Status](https://travis-ci.org/otto-de/tesla-mongo-connect.svg)](https://travis-ci.org/otto-de/tesla-mongo-connect)
[![Dependencies Status](http://jarkeeper.com/otto-de/tesla-mongo-connect/status.svg)](http://jarkeeper.com/otto-de/tesla-mongo-connect)

## Usage

Add this to your project's dependencies:

`[de.otto/tesla-mongo-connect "0.1.4"]`

## Usage

You can initialize several instances of tesla-mongo-connect in your tesla-microservice. In the simple case each one connects to a single database on a mongodb. The configurations for the individual connections are distinguished by prefix:


```
test-db1.mongo.host=localhost
test-db1.mongo.dbname=teslatest
test-db1.mongo.user=
test-db1.mongo.passwd=


test-db2.mongo.host=other-url
test-db2.mongo.dbname=teslaprod
test-db2.mongo.user=user
test-db2.mongo.passwd=passwd

```

Now you can establish two connections like this:

```
(defn example-system [runtime-config]
  (-> (system/empty-system (merge {:name "mongo-example-service"} runtime-config))
      (assoc :mongo1
             (c/using (mongo/new-mongo "test-db1") [:config :metering :app-status]))
      (assoc :mongo2
             (c/using (mongo/new-mongo "test-db2") [:config :metering :app-status]))
      (assoc :foo (foo/new-foo) [:mongo1 :mongo2])
      (c/system-using {:server [:example-page]})))
```

In the component ```:foo``` you could then find the document with the id of "foo" in the collection "my-collection" in the database  ```:mongo1```  like this:

```
  (mongo/find-one-checked! (:mongo1 self) "my-collection" {:_id "foo"})
```


For a working example see the included example code.

## TODO
* Add description for (optional) dbname switching functionality


## Initial Contributors

Christian Stamm, Kai Brandes, Daley Chetwynd, Felix Bechstein, Ralf Sigmund, Florian Weyandt

## License

Apache License
