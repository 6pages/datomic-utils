(ns user
  (:require
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as ds]
   [com.6pages.datomic.transact :as dt]))

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
  (def db-name
    (rand-nth
     (d/list-dbs {:client client})))
  (def opts
    {:client client :db-name db-name})

  (ds/get-schema opts)

  (d/q opts '[:find (pull ?e [*])
              :where [?e :user/email _]])

  
  
  ,)
