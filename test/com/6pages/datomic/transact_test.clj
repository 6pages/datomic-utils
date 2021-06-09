(ns com.6pages.datomic.transact-test
  (:require
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as ds]
   [com.6pages.datomic.transact :as dt])
  (:import
   [java.util Date UUID]))


(def client-cfg
  {:server-type :dev-local
   :storage-dir "/tmp/datomic/dev-local/storage"
   :system "dev"})

(defmacro with-datomic-opts
  [sym & body]
  `(let [~sym {:client (d/client ~client-cfg)
               :db-name "dev"}]
     ~@body))

(def schemas
  (-> "datomic/example/schema.edn"
      io/resource slurp
      edn/read-string))

(defmacro with-transact-opts
  [sym & body]
  `(let [~sym {:schemas ~schemas
               :unique-attrs
               (ds/schemas->unique-attrs ~schemas)}]
     ~@body))

(defmacro with-db
  [opts & body]
  `(try
     (d/create-db ~opts)
     (ds/update! ~opts ~schemas)
     (ds/get-schema ~opts)

     ~@body

     (finally
       (datomic.client.api/delete-database
        (:client ~opts)
        {:db-name (:db-name ~opts)}))))


(defn ->person
  ([]
   {:person/id (UUID/randomUUID)
    :person/name (gen/string)}))

(deftest returns-db-id
  (with-datomic-opts dopts
    (with-db dopts
      (with-transact-opts topts

        (let [pd (->person)
              tr (dt/entity->transact! dopts topts pd)
              qr (d/p-> dopts ['*] [[:person/id (:person/id pd)]])]
          (is
           (= (:db/id tr)
              (:db/id qr))))))))
