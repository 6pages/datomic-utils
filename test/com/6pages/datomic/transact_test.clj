(ns com.6pages.datomic.transact-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [com.6pages.datomic :as d]
   [com.6pages.datomic.schema :as ds]
   [com.6pages.datomic.transact :as dt]))
