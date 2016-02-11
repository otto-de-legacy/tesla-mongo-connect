(ns de.otto.tesla.mongo.mongo
  (:require [com.stuartsierra.component :as component]
            [monger.core :as mg]
            [monger.query :as mq]
            [monger.collection :as mc]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [metrics.timers :as timers]
            [de.otto.tesla.stateful.metering :as metering]
            [de.otto.tesla.stateful.app-status :as app-status]
            [de.otto.status :as s]
            [metrics.counters :as counters])
  (:import com.mongodb.ReadPreference
           (com.mongodb MongoException MongoCredential MongoClient)))

(defn property-for-db [conf which-db property-name]
  (get conf (keyword (str which-db "-mongo-" (name property-name)))))

(defn parse-port [conf prop]
  (Integer.
    (if-let [port (prop "port")]
      port
      (:default-mongo-port conf))))

(defn parse-server-address [conf prop]
  (let [host (prop "host")
        port (parse-port conf prop)]
    (if (.contains host ",")
      (map #(mg/server-address % port) (str/split host #","))
      (mg/server-address host port))))

(def read-preference {:primary-preferred   (ReadPreference/primaryPreferred)
                      :primary             (ReadPreference/primary)
                      :secondary-preferred (ReadPreference/secondaryPreferred)
                      :secondary           (ReadPreference/secondary)
                      :nearest             (ReadPreference/nearest)})

(defn default-options [prop]
  {:socket-timeout                                     (if-let [st (prop :socket-timeout)]
                                                         (read-string st)
                                                         31)
   :connect-timeout                                    (if-let [ct (prop :connect-timeout)]
                                                         (read-string ct)
                                                         2000)
   :socket-keep-alive                                  (= "true" (prop :socket-keep-alive))
   :threads-allowed-to-block-for-connection-multiplier 30
   :read-preference                                    ((or (prop :read-preference) :secondary-preferred) read-preference)})

(defn read-timer-name [db]
  (str "mongo." db ".read"))

(defn prop-resolution-fun [prop]
  (log/info "choosing prop-dbname-resolution-fun to determine dbname")
  (let [from-property (prop "dbname")]
    (fn [] from-property)))

(defn resolve-db-name [self]
  ((:dbname-fun self)))

(defn create-mongo-credential [prop dbname]
  (let [user (prop "user")
        password (prop "passwd")]
    (if (not (str/blank? user))
      [(MongoCredential/createCredential user dbname (.toCharArray password))]
      [])))

(defn create-client [conf prop dbname]
  (let [server-address (parse-server-address conf prop)
        cred (create-mongo-credential prop dbname)
        options (mg/mongo-options (default-options prop))]
    (MongoClient. server-address cred options)))


(defn authenticated-db [conf prop dbname]
  (try
    (.getDB (create-client conf prop dbname) dbname)
    (catch MongoException e
      (log/error e "error authenticating mongo-connection")
      :not-connected)))

(defn nil-if-not-connected [db]
  (if (= :not-connected db)
    nil
    db))

(defn new-db-connection [dbNamesToConns conf prob dbname]
  (log/info "initializing new connection for db-name " dbname)
  (let [db (authenticated-db conf prob dbname)]
    (swap! dbNamesToConns #(assoc % dbname db))
    (nil-if-not-connected db)))

(defn db-by-name [self dbname]
  (if-let [db (get @(:dbNamesToConns self) dbname)]
    (nil-if-not-connected db)
    (new-db-connection (:dbNamesToConns self) (:conf self) (:prop self) dbname)))

(defn status-fun [self]
  (s/status-detail
    (keyword (str "mongo-" (:which-db self)))
    :ok
    "mongo"
    {:active-dbs (keys @(:dbNamesToConns self))
     :current-db (resolve-db-name self)}))

(defprotocol DbNameLookup
  (dbname-lookup-fun [self]))

(defrecord Mongo [which-db config metering app-status dbname-lookup]
  component/Lifecycle
  (start [self]
    (log/info (str "-> starting mongodb " which-db))
    (let [conf (:config config)
          prop (partial property-for-db conf which-db)
          new-self (assoc self
                     :conf conf
                     :prop prop
                     :dbNamesToConns (atom {})
                     :dbname-fun (if (nil? dbname-lookup)
                                   (prop-resolution-fun prop)
                                   (dbname-lookup-fun dbname-lookup))
                     :read-timer (metering/timer! metering (read-timer-name which-db))
                     :insert-timer (metering/timer! metering (str "mongo." which-db ".insert"))
                     :exception-counter (metering/counter! metering (str "mongo." which-db ".exceptions")))]
      (app-status/register-status-fun app-status (partial status-fun new-self))
      (new-db-connection (:dbNamesToConns new-self) conf prop ((:dbname-fun new-self)))
      new-self))

  (stop [self]
    (log/info "<- stopping mongodb")
    (map (fn [_ v] (mg/disconnect v)) @(:dbNamesToConns self))
    self))

(defn current-db [self]
  (db-by-name self (resolve-db-name self)))

(defn- clear!
  "removes everything from a collection. Only for tests."
  [self col]
  (if (not (and (.contains col "test") (.contains (resolve-db-name self) "test")))
    (throw (IllegalArgumentException. "won't clear")))
  (mc/remove (current-db self) col)
  :ok)


(defn update-upserting!
  [self col query doc]
  (timers/time! (:insert-timer self)
                (mc/update (current-db self) col query doc {:upsert true})))

(defn- find-one! [self col query fields]
  (log/debugf "mongodb query: %s %s %s" col query fields)
  (timers/time! (:read-timer self)
                (some-> (current-db self)
                        (mc/find-one-as-map col query fields))))

(defn find-one-checked!
  ([self col query]
   (find-one-checked! self col query []))
  ([self col query fields]
   (try
     (find-one! self col query fields)
     (catch MongoException e
       (counters/inc! (:exception-counter self))
       (log/warn e "mongo-exception for query: " query)))))

(defn find! [self col query]
  (log/debugf "mongodb query: %s %s" col query)
  (timers/time! (:read-timer self)
                (some-> (current-db self)
                        (mc/find-maps col query))))

(defn find-checked! [self col query]
  (try
    (find! self col query)
    (catch MongoException e
      (counters/inc! (:exception-counter self))
      (log/warn e "mongo-exception for query: " query))))

(defn count! [self col query]
  (log/debugf "mongodb count: %s %s" col query)
  (timers/time! (:read-timer self)
                (some-> (current-db self)
                        (mc/count col query))))

(defn count-checked! [self col query]
  (try
    (count! self col query)
    (catch MongoException e
      (counters/inc! (:exception-counter self))
      (log/warn e "mongo-exception for query: " query))))

(defn remove-by-id!
  [self col id]
  (mc/remove-by-id (current-db self) col id))

(defn find-ordered [self col query order limit]
  (mq/exec
    (-> (mq/empty-query (.getCollection (current-db self) col))
        (mq/find query)
        (mq/sort order)
        (mq/limit limit))))


(defn insert!
  [self col doc]
  (timers/time! (:insert-timer self)
                (mc/insert-and-return (current-db self) col doc)))

(defn new-mongo
  ([which-db] (map->Mongo {:which-db which-db})))
