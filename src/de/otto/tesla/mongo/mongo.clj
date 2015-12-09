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

(defn default-options [prop]
  {:socket-timeout                                     (if-let [st (prop :socket-timeout)]
                                                         (read-string st)
                                                         31)
   :connect-timeout                                    (if-let [ct (prop :connect-timeout)]
                                                         (read-string ct)
                                                         2000)
   :socket-keep-alive                                  (= "true" (prop :socket-keep-alive))
   :threads-allowed-to-block-for-connection-multiplier 30
   :read-preference                                    (ReadPreference/secondary)})

(defn mongo-options [prop]
  (mg/mongo-options (default-options prop)))

(defn read-timer-name [db]
  (str "mongo." db ".read"))

(defn prop-resolution-fun [prop]
  (log/info "choosing prop-dbname-resolution-fun to determine dbname")
  (let [from-property (prop "dbname")]
    (fn [] from-property)))

(defn resolve-db-name [self]
  ((:dbname-fun self)))

;(defn authenticate-mongo [prop db]
;  (let [u (prop "user")
;        p (prop "passwd")]
;    (if (not (str/blank? u))
;      (let [authenticated (mg/authenticate db u (.toCharArray p))]
;        (log/info (str "authentication success: " authenticated))
;        (log/info (str "Connected. Last error: " (mg/get-last-error db)))))))

(defn create-mongo-credential [prop dbname]
  (let [user (get prop "user")
        password (get prop "passwd")]
    (if (not (str/blank? user))
      [(MongoCredential/createCredential user dbname (.toCharArray password))]
      []
      )
    ))

(defn authenticated-db [conf prop dbname]
  (let [server-address (parse-server-address conf prop)
        cred (create-mongo-credential prop dbname)
        options (mg/mongo-options (default-options prop))]
    (try
      (.getDB (MongoClient. server-address cred options ) dbname)
      (catch MongoException e
        (log/error e "error authenticating mongo-connection")
        :not-connected))))

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
    (new-db-connection (:dbNamesToConns self) (:conf self) (:prob self) dbname)))

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
                     :prob prop
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
    ;(mg/disconnect (:conn self))
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

(defn- find-one! [self col query]
  (log/debugf "mongodb query: %s %s" col query)
  (timers/time! (:read-timer self)
                (some-> (current-db self)
                        (mc/find-one-as-map col query))))

(defn find-one-checked! [self col query]
  (try
    (find-one! self col query)
    (catch MongoException e
      (counters/inc! (:exception-counter self))
      (log/warn e "mongo-exception for query: " query))))

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
