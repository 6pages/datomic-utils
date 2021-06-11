# datomic-utils

Utilities for working with Datomic.

[![Clojars Project](https://img.shields.io/clojars/v/com.6pages/datomic-utils.svg)](https://clojars.org/com.6pages/datomic-utils)

[![](https://cljdoc.org/badge/com.6pages/datomic-utils)](https://cljdoc.org/jump/release/com.6pages/datomic-utils)


## Status

Running in production at [6Pages](https://www.6pages.com) but unstable
and subject to change. Let's call it an _early_ release.


## Features

The most interesting features in the library are:

1. schema migrations
2. query abstraction


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

Do you find yourself writing many queries for one (or more)
attribute & value pairs?

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

### In a Clojure project

1. [add to dependencies](https://clojars.org/com.6pages/datomic-utils)

2. require in a namespace

```clojure
(ns person
  (:require 
    [com.6pages.datomic :as d]
    [com.6pages.datomic.schema :as ds]))
```

3. build Datomic client options

```clojure
(def opts
  {:db-name "dev"
   :client 
    (d/client 
    ;; use your own Datomic client config
    {:server-type :dev-local
     :system "datomic-samples"})})
```

4. apply your schema

```clojure
(def schemas [
  [{:db/ident       :com.6pages.datomic.schema/version
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}]
  [{:db/ident       :person/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :person/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}]])
     
(ds/update! opts schemas)
```

5. transact!

```clojure
(def entity
  (d/transact!
    opts
    [{:person/id (java.util.UUID/randomUUID)
      :person/name "Ada"}]))
```

### REPL and tests

1. [setup dev-local](https://docs.datomic.com/cloud/dev-local.html)
2. `clj -A:local -A:dev` (you may also want to add a REPL server, if you're into that sort of thing)


## Related work

+ [avodonosov/datomic-helpers](https://github.com/avodonosov/datomic-helpers)
+ [halgari/fafnir](https://github.com/halgari/fafnir)
+ [flyingmachine/datomic-junk](https://github.com/flyingmachine/datomic-junk)


## License

Copyright © 2021 6Pages Inc.

Distributed under the [Eclipse Public License](https://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
