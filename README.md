# datomic-utils

Utilities for working Datomic. Built and running in production at
[6Pages](https://www.6pages.com).


## Features

The most interesting features in the library are:

1. transacting deep entities
2. schema migrations
3. query abstraction


### transacting deep entities

You have a schema that includes deeply nested data, like this entity:

```clojure
{:blog/posts
   [
    {:post/title "Crypto eats compute"
     :post/tags
     [{:tag/name "Cryptocurrencies"}]}

    {:post/title "Machine learning eats compute"
     :post/tags
     [
      {:tag/name "Machine Learning"}]}]}
```

Let's say that the `:tag/name` attribute is intended to be unique
(`:db.unique/identity`) and an entity with `:tag/name "Machine
Learning"` already exists in the database.

You want to transact this entity, but it's not a single entity. This
is actually 5 entities. One is already in the database. To transact
it, you would need to:

```clojure
(d/q db '[:find ?e :where [?e :tag/name "Machine Learning"]]) => 1234

(d/transact db
  {:tx-data
    [{:db/id "-9874" :tag/name "Cryptocurrencies"}
     {:db/id "-9875"
      :post/title "Machine learning eats compute"
      :post/tags [1234]}
     {:db/id "-9876"
      :post/title "Crypto eats compute"
      :post/tags ["-9874"]}
     {:blog/posts ["-9876" "-9875"]}])
```

If you also needed the newly created `:db/id` of the `:blog` entity,
then you would need to go extract it from the `d/transact` results or
make another database query.

These are all things that
`com.6pages.datomic.transact/entity->transact!` does for you. When you
give it an entity to transact, this happens:

+ walks the entity to pull out all the child entities
+ check if any of the entities are already in the database (based on attribute uniqueness constraints)
  + if already in the database, determine only attributes that are new or different
+ after the transaction, it updates all the result `:db/id`'s back into the same entity structure

And, it does all this fast. For example, `entity->transact!` uses
`core.async/pipeline` to run all the queries. Performance was a
significant part of the inspiration for this library; querying for
dozens of entities on a single thread can be slow.


### schema migrations

Datomic recommends that we [grow our schema and never break
it](https://docs.datomic.com/cloud/best.html#grow-schema). However,
they leave it up to us (the user) to decide how to manage the
migrations of schema accumulation.

This library includes a simple solution. 

+ Your application code keeps a collection of schemas
+ `com.6pages.datomic.schema` stores an applied schema version number in Datomic
+ whenever you add new schemas (new schema version), `com.6pages.datomic.schema/update!` will make sure that all schema collections have been transacted up to the most recent version

Here's what a collection of schemas might look like:

```clojure
[
 ;; version 0
 [
  ;; schema
  {:db/ident       :com.6pages.datomic.schema/version
   :db/valueType   :db.type/long
   :db/cardinality :db.cardinality/one}]

 ;; version 1
 [
  ;; Person
  {:db/ident       :person/id
   :db/valueType   :db.type/uuid
   :db/unique      :db.unique/identity
   :db/cardinality :db.cardinality/one}
  {:db/ident       :person/name
   :db/valueType   :db.type/string
   :db/cardinality :db.cardinality/one}]

 ;; version 2
 [
  ;; 
  {:db/ident       :person/friend
   :db/valueType   :db.type/ref
   :db/cardinality :db.cardinality/many}]
 ]
```

You must explicitly define the attribute storing the schema version in
your first version (there's a validation exception if you forget).

As you need to add more schema definitions, you simply add another
version collection and run `com.6pages.datomic.schema/update!` on all
your databases.


### query abstraction

The most of my Datomic query look like:

```clojure
(d/q
  db
  '[:find (pull ?e [*])
    :in $ ?id
    :where
    [?e ::id ?id]]
  person-id)
```

This library has some simple abstractions to build these types of
queries.

```clojure
(ns person
  (:require [com.6pages.datomic :as d]))
  
(def opts
  {:client (d/client {}) :db-name "dev"})
  
(d/p-> opts ['*] [[::id id]]) ;; single result

(d/p->> opts ['*] [[::name "Bob"]]) ;; collection of results
```


## Usage


## Questions (FAQ)

### Datomic's transactor has many features. Does it handle X?

Probably not, but augmenting the library is open for discussion. Some
already considered directions are:


#### CAS (compare and set)

You have a database with a heavy transactor load or high likelyhood of
entities changing in a short period of time. If there's a change in
the database between the time that
`entity->transact!` is called and the time
that the transaction is issued.


#### transaction functions




## License

Copyright © 2021 6Pages Inc.

Distributed under the [Eclipse Public License](https://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
