(ns gradient.contract-test
  "Le operazioni scritte nel frontend devono essere valide rispetto a schema.graphql."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.walmartlabs.lacinia.parser :as parser]
            [gradient.graphql :as graphql])
  (:import (java.io PushbackReader)))

(defn frontend-operations []
  (with-open [reader (PushbackReader. (io/reader "frontend/src/gradient/ui/api.cljs"))]
    (binding [*read-eval* false]
      (->> (repeatedly #(read {:eof nil :read-cond :allow} reader))
           (take-while some?)
           (some (fn [form] (when (and (seq? form) (= 'def (first form)) (= 'operations (second form)))
                              (nth form 2))))))))

(deftest frontend-operations-match-schema
  (let [schema (graphql/load-schema)
        operations (frontend-operations)]
    (is (= 8 (count operations)))
    (doseq [[k query] operations]
      (testing (name k)
        (is (some? (parser/parse-query schema query)))))))

(deftest schema-loads-with-all-resolvers
  (is (some? (graphql/load-schema))))
