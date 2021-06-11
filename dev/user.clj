(ns user
  (:require
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as ds])
  (:import
   [java.util Date UUID]))


(comment

  (def client-cfg
    {:server-type :dev-local
     :storage-dir :mem
     :system "dev"})
  (def client (d/client client-cfg))
  (def db-name "dev")
  (def schemas (-> "datomic/example/schema.edn"
                   io/resource slurp
                   edn/read-string))

  (def opts {:client client :db-name db-name})

  (d/create-db opts)
  (ds/update! opts schemas)
  (ds/get-schema opts)


  (def ids (repeatedly 3 #(UUID/randomUUID)))
  (dt/transact!
   opts
   {:person/id (first ids)
    :person/name (gen/string)})

  (d/p-> opts '[*] [[:person/id (first ids)]])

  ,)

(comment

  (def client (d/client client-cfg))
  (def db-name "mbrainz-subset")
  (def opts {:client client :db-name db-name})

  ;; mbrainz schema: https://github.com/Datomic/mbrainz-sample/wiki/Schema
  (ds/get-schema opts)

  (d/q opts '[:find (count ?e)
              :where [?e :label/name _]])

  (d/p-> opts '[*] [[:label/name "Universal"]])

  ,)
