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


### query abstraction



## Usage


## Questions

### Datomic's transactor has feature. Does it handle X?

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
