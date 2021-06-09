(ns com.6pages.datomic.transact
  (:require
   [clojure.core.async :as async :refer [<!! chan onto-chan pipeline]]
   [clojure.set :refer [difference intersection]]
   [clojure.spec.alpha :as s]
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as schema]))


;;
;; spec

(s/def ::entity
  (s/map-of ident? any?))

(s/def ::path
  (s/coll-of (s/or :ident ident? :int integer?)))


;;
;; temp id

(defn ->temp-id
  ([] (->temp-id (* -1 (rand-int 10000))))
  ([i]
   (let [t (atom i)]
     (fn
       ([] (str (swap! t dec)))
       ([base] (str base (swap! t dec)))))))


;;
;; entity->retract!


(defn id->retract-fact
  [id]
  {:pre [(s/valid? number? id)]}
  [:db/retractEntity id])

(defn entity->retract-fact
  [entity]
  {:pre [(s/valid? number? (:db/id entity))]}
  (->> entity :db/id id->retract-fact))

(defn entity->retract!
  [opts entity]
  {:pre [(s/valid? number? (:db/id entity))]}
  (->> entity
       entity->retract-fact
       vector
       (d/transact! opts)))


;;
;; opts / options

(defn attribute-predicates
  [{:keys [:schemas] :as opts}]
  {:pre [(s/valid? ::schema/coll schemas)]}
  (let [cardk->attrs #(->> [[:db/valueType :db.type/ref]
                            [:db/cardinality %]]
                           (apply schema/->filter schemas)
                           (map :db/ident)
                           set)
        mas (cardk->attrs :db.cardinality/many)
        ras (cardk->attrs :db.cardinality/one)]
    {:attr->many? #(contains? mas %)
     :attr->ref? #(contains? ras %)}))

(defn opts->ensure
  [{:keys [:attr->many? :attr->ref? :temp-id] :as opts}]
  (cond-> opts
    (nil? temp-id)
    (assoc :temp-id (->temp-id))

    (or (nil? attr->many?) (nil? attr->ref?))
    (merge (attribute-predicates opts))))


;;
;; unique->q

(defn entity->update-idents
  [entity]
  (reduce-kv
   (fn [e attr v]
     (let [vv (if-some [i (:db/ident v)] i v)]
       (assoc e attr vv)))
   {} entity))

(defn unique->q
  [dopts unique-ks entity]
  (let [attrs (select-keys entity unique-ks)]
    (when-not (empty? attrs)
      (some->> attrs
               (d/->query ['*])
               (d/q dopts)
               ;; TODO exception for many results (should only be 1, if
               ;; truly unique)
               ffirst
               entity->update-idents))))


;;
;; entity->update-db-ids

(defn entity->unique-attr-pairs
  [{:keys [:unique-attrs]} entity]
  {:pre [(s/valid? (s/coll-of ident?) unique-attrs)]}
  (intersection
   (set unique-attrs)
   (-> entity keys set)))

(defn entity->assoc-db
  "try to load from database based on unique attributes. If found in
  database, add :db/entity with entity from database."
  [dopts opts entity]
  ;; check for unique attrs to query database
  (let [uaps (entity->unique-attr-pairs opts entity)]
    (if-not (seq uaps) entity
            ;; has unique keys!
            (if-some [e (unique->q dopts uaps entity)]
              (assoc entity :db/entity e)
              entity))))


;;
;; entity->flatten

(defn entity->flatten
  [opts entity]
  (let [{:keys [:attr->many? :attr->ref? :temp-id]} opts
        e->id #(or (:db/id %) %)]
    (->> entity
         (reduce-kv
          (fn [{:keys [:e :es]} attr v]
            (let [concat-es #(->> % flatten (concat es) (into []))]
              (cond
                (attr->many? attr)
                (let [necs (map #(entity->flatten opts %) v)]
                  {:e (assoc e attr (mapv (comp e->id last) necs))
                   :es (concat-es necs)})

                (and (attr->ref? attr) (map? v))
                (let [ne (entity->flatten opts v)
                      eid (-> ne last e->id)]
                  {:e (assoc e attr eid)
                   :es (if-not (or (number? eid) (string? eid))
                         es ;; must be ident
                         (concat-es ne))})

                :default
                {:e (assoc e attr v) :es es})))
          {:e {} :es []})
         ;; ensure entity has :db/id
         (#(update-in % [:e :db/id]
                      (fn [i]
                        (or i (temp-id)))))
         ((juxt :es :e))
         (apply conj))))


;;
;; entity->ensure-id

(defn entity->ensure-id
  [entity]
  (if-some [dbe (:db/entity entity)]
    (let [eid  (:db/id entity)
          dbid (:db/id dbe)
          rp (when-not (= eid dbid) {eid dbid})]
      [rp (assoc entity :db/id dbid)])
    [nil entity]))


;;
;; entity->diff

(defn entities->diff
  [& entities]
  {:pre [(s/valid? (s/coll-of map?) entities)]}
  (let [into-set #(->> % (into []) set)]
    (->> entities
         (map into-set)
         (apply difference)
         (reduce #(apply assoc %1 %2) {}))))

(defn entity->diff
  [entity]
  (if-some [dbe (:db/entity entity)]
    (let [dbid (:db/id dbe)]
      (cond-> (entities->diff entity dbe)
        dbid ;; ensure :db/id from :db/entity
        (assoc :db/id dbid)))
    entity))

(defn entity->empty?
  "entity->diff removed all other attributes"
  [entity]
  (and
   (:db/id entity)
   (-> entity keys count (= 1))))


;;
;; entity->facts

(defn ->add-fact
  [id attr v]
  [:db/add id attr v])

(defn entity->add-facts
  [entity]
  (let [id (:db/id entity)]
    (->> entity
         (#(dissoc % :db/entity :db/id))
         (reduce-kv
          (fn [facts attr v]
            (let [afn #(->add-fact id attr %)
                  vv (if (coll? v) v [v])]
              (->> vv (mapv afn)
                   (concat facts) (into []))))
          []))))

(defn entity->facts
  [entity]
  (let [id (:db/id entity)]
    (cond
      (number? id)      ;; update
      (entity->add-facts entity)

      :else [entity]))) ;; create


;;
;; entity->replace-ids

(defn entity->replace-ids
  "assume entity is already flat; refs replaced with ids"
  [opts id->id entity]
  {:pre [(s/valid? map? id->id)]}
  (let [{:keys [:attr->many? :attr->ref?]} opts]
    (reduce-kv
     (fn [a attr v]
       (assoc
        a attr
        (cond
          (= :db/id attr) (get id->id v v)
          
          (attr->many? attr)
          (mapv #(get id->id % %) v)
          
          (attr->ref? attr)
          (get id->id v v)
          
          :default v)))
     {} entity)))


;;
;; entity->clean-attrs

(defn entity->clean-attrs
  [opts entity]
  (let [{:keys [:schemas]} opts
        ks (->> schemas flatten
                (mapv :db/ident)
                (concat [:db/id :db/entity])
                (into []))]
    (select-keys entity ks)))


;;
;; entity->dissoc

(defn entity->dissoc
  [opts ks entity]
  {:pre [(s/valid? :spec/seq ks)]}
  (apply dissoc entity ks))


;;
;; flat-entities->entity

(defn flat-entities->entity
  "assumes that root is (last [fes])"
  ([opts fes]
   (let [id->fe (reduce #(assoc %1 (:db/id %2) %2) {} fes)]
     (flat-entities->entity opts id->fe (last fes))))
  
  ([opts id->fe root]
   (let [{:keys [:attr->many? :attr->ref?]} opts
         rekur #(->> % (get id->fe)
                     (flat-entities->entity opts id->fe))]
     (if-not
         (map? root) root
         (reduce-kv
          (fn [a attr v]
            (assoc
             a attr
             (cond
               (attr->many? attr)
               (mapv rekur v)
               
               (attr->ref? attr)
               (if (ident? v) v (rekur v))
               
               :default v)))
          {} root)))))


;;
;; flat-entities->loaded

(defmulti flat-entities->loaded
  (fn [_ {:keys [:async]} _]
    (if (= async false) :single-thread :async)))

(defmethod flat-entities->loaded :async
  [dopts opts fes]
  {:pre [(s/valid? (s/coll-of ::entity) fes)]}
  (let [opts (opts->ensure opts)
        ech (chan) dpch (chan)]
    (onto-chan ech fes)

    (pipeline
     50 dpch  ;; low gain when > 50 (though may depend on size of [fes])
     (comp
      (map #(entity->assoc-db dopts opts %))
      (map entity->ensure-id))
     ech)

    (let [erps (<!! (async/into [] dpch))
          id->id (->> erps (map first)
                      (reduce merge {}))
          es (mapv second erps)]
      [id->id es])))

(defmethod flat-entities->loaded :single-thread
  [dopts opts fes]
  {:pre [(s/valid? (s/coll-of ::entity) fes)]}
  (let [opts (opts->ensure opts)
        erps (->> fes
                  (map #(entity->assoc-db dopts opts %))
                  (map entity->ensure-id))
        id->id (->> erps (map first)
                    (reduce merge {}))
        es (mapv second erps)]
    [id->id es]))


;;
;; flat-entities->facts

(defmulti flat-entities->facts
  (fn [_ {:keys [:async]} _ _]
    (if (= async false) :single-thread :async)))

(defmethod flat-entities->facts :async
  [dopts opts id->id fes]
  {:pre [(s/valid? map? id->id)
         (s/valid? (s/coll-of ::entity) fes)]}
  (let [opts (opts->ensure opts)
        from-ch (chan) to-ch (chan)]
    (onto-chan from-ch fes)
    (pipeline
     3 to-ch
     (comp
      ;; clean before diff to avoid extra comparisons
      (map #(entity->clean-attrs opts %))
      (map entity->diff)
      (map #(dissoc % :db/entity))
      (remove entity->empty?)
      (map #(entity->replace-ids opts id->id %))
      (mapcat entity->facts))
     from-ch)
    (<!! (async/into [] to-ch))))

(defmethod flat-entities->facts :single-thread
  [dopts opts id->id fes]
  {:pre [(s/valid? map? id->id)
         (s/valid? (s/coll-of ::entity) fes)]}
  (let [opts (opts->ensure opts)]
    (->> fes
         ;; clean before diff to avoid extra comparisons
         (map #(entity->clean-attrs opts %))
         (map entity->diff)
         (map #(dissoc % :db/entity))
         (remove entity->empty?)
         (map #(entity->replace-ids opts id->id %))
         (mapcat entity->facts)
         (into []))))


;;
;; entity->delta-facts

(defn entity->delta-facts
  [dopts opts entity]
  (let [opts (opts->ensure opts)]
    (->> entity
         (entity->flatten opts)
         (flat-entities->loaded dopts opts)
         (apply flat-entities->facts dopts opts))))


;;
;; entity->transact!

(defn entity->transact!
  [dopts opts entity]
  {:pre [(s/valid? ::entity entity)]}
  (let [opts (opts->ensure opts)
        lrs (->> entity
                 (entity->flatten opts)
                 (flat-entities->loaded dopts opts))
        facts (apply flat-entities->facts dopts opts lrs)
        txrs (d/transact! dopts facts)]
    (->> lrs second  ;; flat entities with updated :db/id's
         (map #(dissoc % :db/entity))
         (map #(entity->replace-ids opts (first lrs) %))
         (map #(entity->replace-ids opts (:tempids txrs) %))
         (flat-entities->entity opts))))
