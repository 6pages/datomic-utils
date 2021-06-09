(ns user
  (:require
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as ds]
   [com.6pages.datomic.transact :as dt])
  (:import
   [java.util Date UUID]))


;;
;; configuration
;;  use your own Datomic configuration here
;;  or, follow https://docs.datomic.com/cloud/dev-local.html

(def client-cfg
  {:server-type :dev-local
   :storage-dir "/tmp/datomic/dev-local/storage"
   :system "datomic-samples"})

(comment

  ;; REPL session

  (def client (d/client client-cfg))
  (def db-name "mbrainz-subset")
  (def opts {:client client :db-name db-name})

  ;; mbrainz schema: https://github.com/Datomic/mbrainz-sample/wiki/Schema
  (ds/get-schema opts)

  (d/q opts '[:find (count ?e)
              :where [?e :label/name _]])

  (d/p-> opts '[*] [[:label/name "Universal"]])

  ,)



(comment

  (def client (d/client client-cfg))
  (def db-name "dev")
  (def schemas (-> "datomic/example/schema.edn"
                   io/resource slurp
                   edn/read-string))

  (def opts {:client client :db-name db-name})
  (def topts
    {:schemas (flatten schemas)
     :unique-attrs (ds/schemas->unique-attrs schemas)})

  (d/create-db opts)
  (ds/update! opts schemas)
  (ds/get-schema opts)

  (dt/entity->delta-facts
   opts topts
   {:person/id (UUID/randomUUID)
    :person/name (gen/string)})

  (def ids (repeatedly 3 #(UUID/randomUUID)))
  (dt/entity->transact!
   opts topts
   {:person/id (first ids)
    :person/name (gen/string)})

  (d/p-> opts '[*] [[:person/id (first ids)]])

  
  (dt/entity->transact!
   opts topts
   {:person/id (first ids)
    :person/friend
    [{:person/id (second ids)
      :person/name (gen/string)}]})

  (d/p-> opts '[*] [[:person/id (first ids)]])
  
  ,)
