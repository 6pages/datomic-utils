(ns com.6pages.datomic.schema
  (:require
   [clojure.spec.alpha :as s]
   [com.6pages.datomic :as d]))

;;
;; spec

(s/def ::def
  #(and
    (map? %)
    (-> % :db/ident ident?)))

(s/def ::by-version
  (s/coll-of
   (s/coll-of ::def)))

(s/def ::coll
  (s/or
   :flat          (s/coll-of ::def)
   :by-migrations ::by-version))


;;
;; schemas

(defn get-schema
  [opts]
  (d/q
   opts
   '[:find ?attr ?type ?card
     :where
     [_ :db.install/attribute ?a]
     [?a :db/valueType ?t]
     [?a :db/cardinality ?c]
     [?a :db/ident ?attr]
     [?t :db/ident ?type]
     [?c :db/ident ?card]]))

(defn ->validate
  [schemas]
  {:pre [(s/valid? ::coll schemas)]}
  (assert
   (->> schemas flatten
        (map :db/ident)
        (some #(= % ::version)))
   "schemas must include :com.6pages.datomic.schema/version in the first version"))

(defn ->filter
  [schemas & filter-pairs]
  {:pre [(s/valid? ::coll schemas)
         (s/valid?
          (s/coll-of
           (s/tuple keyword? :spec/k-or-fn))
          filter-pairs)]}
  (let [f (fn [k-or-fn]
            (if (fn? k-or-fn) k-or-fn #(= k-or-fn %)))
        fs (map
            (fn [[attr v]]
              #(-> % (get attr) ((f v))))
            filter-pairs)
        ff (fn [se]
             (reduce #(and %1 (%2 se)) true fs))]
    (->> schemas
         flatten
         (filter ff))))


;;
;; version

(defn version-exists?
  [opts]
  (contains?
   (d/attr->selector opts ::version [:db/ident])
   :db/ident))

(defn version-0->transact!
  [opts schemas]
  {:pre [(s/valid? ::by-version schemas)]}
  (let [sv0 (first schemas)]
    ;; transact schema with ::version
    (d/transact! opts sv0)
    ;; transact schema entity
    (d/transact! opts [{::version 0}])))

(defn version->ensure!
  [opts schemas]
  (when-not (version-exists? opts)
    (->validate schemas)
    (version-0->transact! opts schemas)))

(defn version
  [opts]
  (ffirst
   (d/q
    opts
    '[:find (pull ?e [*])
      :where [?e ::version _]])))

(defn update!
  [opts schemas]
  {:pre [(s/valid? ::by-version schemas)]}
  (->validate schemas)
  (version->ensure! opts schemas)
  (let [svd (version opts)
        nums (range
              (inc (::version svd))
              (count schemas))]

    ;; apply schema versions in order
    (doseq [v nums]
      (let [schema (get schemas v)
            ;; update ::version as schema
            ;; definitions are applied. In case there is
            ;; an error, we don't want to be applying the
            ;; existing schema definitions again
            tx-schema-version [:db/add (:db/id svd) ::version v]
            txns (conj schema tx-schema-version)]
        (d/transact! opts txns)))))


;;
;; unique attributes

(defn schemas->unique-attrs
  [schemas]
  {:pre [(s/valid? ::coll schemas)]}
  (->> [:db/unique keyword?]
       (->filter schemas)
       (map :db/ident)))
