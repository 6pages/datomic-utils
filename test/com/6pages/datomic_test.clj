(ns com.6pages.datomic-test
  (:require
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as ds])
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

(deftest pull-single
  (with-datomic-opts opts
    (with-db opts

      (let [pd (->person)
            _ (d/transact! opts pd)
            qr (d/p-> opts ['*] [[:person/id (:person/id pd)]])]
        (is
         (every?
          (fn [k]
            (= (get pd k) (get qr k)))
          (keys pd)))))))
