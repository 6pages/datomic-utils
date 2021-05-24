(ns com.6pages.datomic
  (:require
   [clojure.spec.alpha :as s]
   [com.6pages.datomic.utils :refer [with-retry]]
   [com.stuartsierra.component :as component]
   [datomic.client.api :as d]))

(s/def ::client any?)
(s/def ::db-name string?)
(s/def ::opts
  (s/keys :req-un [::client ::db-name]))


;;
;; connection

(defn conn
  [opts]
  {:pre [(s/valid? ::opts opts)]}
  (let [{:keys [client db-name]} opts]
    (with-retry
      #(d/connect client {:db-name db-name}))))


;;
;; query interface

(defn q
  [opts query & args]
  {:pre [(s/valid? ::opts opts)]}
  (let [conn (conn opts)
        db (d/db conn)]
    (apply d/q query db args)))

(defn pull
  ([opts eid] (pull opts '[*] eid))
  
  ([opts pull-expr eid]
   {:pre [(s/valid? ::opts opts)
          (s/valid? number? eid)]}
   (let [conn (conn opts)
         db (d/db conn)]
     (d/pull db pull-expr eid))))

(defn attr->selector
  [opts attr-k sel]
  {:pre [(s/valid? ::opts opts)
         (s/valid? keyword? attr-k)
         (s/valid? (s/coll-of keyword?) sel)]}
  (let [conn (conn opts)
        db (d/db conn)]
    (d/pull db {:eid attr-k :selector sel})))


;;
;; pull by attribute (helpers)

(defn ->query
  [pull-expr pairs]
  {:pre [(s/valid?
          (s/or :c (s/coll-of (s/tuple ident? any?))
                :m (s/map-of ident? any?))
          pairs)]}
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
   {:pre [(s/valid? ::opts opts)
          (s/valid? inst? inst)
          (s/valid? number? eid)]}
   (let [conn (conn opts)
         db (d/db conn)
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
  {:pre [(s/valid? ::opts opts)
         (s/valid? number? eid)]}
  (let [conn (conn opts)
        db (d/db conn)
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
  {:pre [(s/valid? ::opts opts)
         (s/valid? number? eid)]}
  (let [conn (conn opts)
        db (d/db conn)
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
;; create database

(defn create-db
  [opts]
  {:pre [(s/valid? ::opts opts)]}
  (let [{:keys [client db-name]} opts]
    (d/create-database client {:db-name db-name})))


;;
;; transact!

(defn transact!
  [opts facts]
  {:pre [(s/valid? (s/coll-of any?) facts)]}
  (-> opts
      conn
      (d/transact {:tx-data facts})))
