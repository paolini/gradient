(ns gradient.contract-test
  "Le operazioni scritte nel frontend devono essere valide rispetto a schema.graphql."
  (:require [clojure.test :refer [deftest is testing]]
            [com.walmartlabs.lacinia.parser :as parser]
            [gradient.graphql :as graphql]
            [gradient.ui.operations :refer [operations]]))

(deftest frontend-operations-match-schema
  (let [schema (graphql/load-schema)]
    (is (= 15 (count operations)))
    (doseq [[k query] operations]
      (testing (name k)
        (is (some? (parser/parse-query schema query)))))))

(deftest schema-loads-with-all-resolvers
  (is (some? (graphql/load-schema))))
