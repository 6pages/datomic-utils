(ns com.6pages.datomic
  (:require
   [clojure.spec.alpha :as s]
   [com.6pages.datomic.utils :refer [with-retry]]
   [datomic.client.api :as d]))

;;
;; client

(s/def ::server-type keyword?)
(s/def ::system string?)

(s/def ::client-cfg
  (s/keys :req-un [::server-type ::system]))

(defn client
  [cfg]
  {:pre [(s/valid? ::client-cfg cfg)]}
  (d/client cfg))


;;
;; connection

(s/def ::client any?)
(s/def ::db-name string?)

(s/def ::opts
  (s/keys :req-un [::client ::db-name]))

(defn conn
  [opts]
  {:pre [(s/valid? ::opts opts)]}
  (let [{:keys [client db-name]} opts]
    (with-retry
      #(d/connect client {:db-name db-name}))))

(defn db
  [opts]
  (d/db (conn opts)))


;;
;; database

(defn create-db
  [opts]
  {:pre [(s/valid? ::opts opts)]}
  (let [{:keys [client db-name]} opts]
    (d/create-database client {:db-name db-name})))

(defn list-dbs
  [opts]
  {:pre [(s/valid? ::client (:client opts))]}
  (d/list-databases (:client opts) {}))


;;
;; query interface

(defn q
  [opts query & args]
  {:pre [(s/valid? ::opts opts)]}
  (let [db (db opts)]
    (apply d/q query db args)))

(defn pull
  ([opts eid] (pull opts '[*] eid))
  
  ([opts pull-expr eid]
   {:pre [(s/valid? number? eid)]}
   (let [db (db opts)]
     (d/pull db pull-expr eid))))

(defn attr->selector
  [opts attr-k sel]
  {:pre [(s/valid? keyword? attr-k)
         (s/valid? (s/coll-of keyword?) sel)]}
  (let [db (db opts)]
    (d/pull db {:eid attr-k :selector sel})))


;;
;; pull by attribute (helpers)

(s/def ::query-pairs
  (s/or
   :c (s/coll-of (s/tuple ident? any?))
   :m (s/map-of ident? any?)))

(defn ->query
  [pull-expr pairs]
  {:pre [(s/valid? ::query-pairs pairs)]}
  `[:find (~'pull ~'?e ~pull-expr)
    :where
    ~@(map
       (fn [[k v]]
         (vector '?e k v))
       pairs)])

(defn p->
  [opts pull-expr kvps]
  (some->> kvps
           (->query pull-expr)
           (q opts)
           ffirst))

(defn p->>
  [opts pull-expr kvps]
  (some->> kvps
           (->query pull-expr)
           (q opts)
           flatten))



;;
;; history

(defn pull-as-of
  ([opts inst eid]
   (pull-as-of opts inst ['*] eid))
  
  ([opts inst pull-expr eid]
   {:pre [(s/valid? inst? inst)
          (s/valid? number? eid)]}
   (let [db (db opts)
         dbasof (d/as-of db inst)]
     (d/pull dbasof pull-expr eid))))

(defn pull-from-db-as-of
  ([db inst eid]
   (pull-from-db-as-of db inst ['*] eid))
  
  ([db inst pull-expr eid]
   {:pre [(s/valid? inst? inst)
          (s/valid? number? eid)]}
   (let [dbasof (d/as-of db inst)]
     (d/pull dbasof pull-expr eid))))

(defn attribute-history
  [opts eid]
  {:pre [(s/valid? number? eid)]}
  (let [db (db opts)
        hdb (d/history db)]
    (->> (d/q
          '[:find ?aname ?v ?inst
            :in $ ?e
            :where [?e ?a ?v ?tx true]
            [?tx :db/txInstant ?inst]
            [?a :db/ident ?aname]]
          hdb eid)
         (sort-by #(nth % 2)))))

(defn entity-history
  [opts eid]
  {:pre [(s/valid? number? eid)]}
  (let [db (db conn)
        hdb (d/history db)]
    (->> (d/q
          '[:find ?inst
            :in $ ?e
            :where [?e _ _ ?tx true]
            [?tx :db/txInstant ?inst]]
          hdb eid)
         (map first)
         sort
         (mapv
          (fn [inst]
            (let [entity (pull-from-db-as-of db inst eid)]
              [inst entity]))))))


;;
;; transact!

(defn transact!
  [opts facts]
  {:pre [(s/valid? (s/coll-of any?) facts)]}
  (-> opts conn
      (d/transact {:tx-data facts})))
