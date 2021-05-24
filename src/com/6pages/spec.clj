(ns com.6pages.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def :spec/k-or-fn
  (s/or :k keyword? :fn fn?))
