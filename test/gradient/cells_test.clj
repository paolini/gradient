(ns gradient.cells-test
  "Interpretazione dei valori delle celle (stessi casi di tests/test_cells.py in main)."
  (:require [clojure.test :refer [are deftest is testing]]
            [gradient.cells :as cells]))

(def all-modifiers {:modifiers ["+" "-" "L"]})

(defn- rejected? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e (boolean (cells/cell-error? e)))))

(defn- message [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest parse-grade
  (are [value expected] (= expected (cells/parse-grade value all-modifiers))
    "7" [7M ""]
    "7,5" [7.5M ""]
    " 7.25 " [7.25M ""]
    "7+" [7M "+"]
    "8-" [8M "-"]
    "30L" [30M "L"]
    "30l" [30M "L"]
    "30 e lode" [30M "L"]
    "30 E LODE" [30M "L"]
    "0" [0M ""]
    "" nil
    "   " nil))

(deftest rejected-grades
  (doseq [value ["7,5+" "7.5L" "abc" "7++" "30L+" "7," ",5" "1,23456" "1000000"]]
    (testing value
      (is (rejected? #(cells/parse-grade value all-modifiers))))))

(deftest missing-is-not-zero
  (is (nil? (cells/parse-grade "" all-modifiers)))
  (is (= [0M ""] (cells/parse-grade "0" all-modifiers))))

(deftest modifiers-follow-column-configuration
  (is (re-find #"non è ammesso" (message #(cells/parse-grade "7+" {}))))
  (is (= [7M "+"] (cells/parse-grade "7+" {:modifiers ["+"]})))
  (is (rejected? #(cells/parse-grade "30L" {:modifiers ["+" "-"]}))))

(deftest scale-limits
  (let [scale {:maximum 10M}]
    (is (cells/parse-grade "10" scale))
    (is (re-find #"massimo" (message #(cells/parse-grade "10,5" scale))))
    (is (re-find #"negativi" (message #(cells/parse-grade "-1" scale)))))
  (let [relaxed {:maximum 10M :allow-above-maximum true :allow-negative true}]
    (is (= [11M ""] (cells/parse-grade "11" relaxed)))
    (is (= [-0.25M ""] (cells/parse-grade "-0,25" relaxed)))))

(deftest negative-with-modifier-is-distinct-from-minus-modifier
  (let [scale {:allow-negative true :modifiers ["-"]}]
    (is (= [-2M ""] (cells/parse-grade "-2" scale)))
    (is (= [2M "-"] (cells/parse-grade "2-" scale)))))

(deftest format-grade-keeps-modifier-and-drops-trailing-zeros
  (is (= "7,5" (cells/format-grade 7.5000M "")))
  (is (= "30L" (cells/format-grade 30.0000M "L")))
  (is (= "100" (cells/format-grade 100M ""))))

(deftest grade-violation-for-existing-values
  (is (nil? (cells/grade-violation 7M "+" {:modifiers ["+"]})))
  (is (some? (cells/grade-violation 7M "+" {})))
  (is (some? (cells/grade-violation 12M "" {:maximum 10M}))))

(deftest parse-flag-three-states
  (are [value expected] (= expected (cells/parse-flag value))
    "sì" true "SI" true "s" true "x" true "1" true
    "no" false "N" false "0" false "" nil "  " nil))

(deftest two-state-flags-treat-empty-as-off
  (is (false? (cells/parse-flag "" false)))
  (is (rejected? #(cells/parse-flag "forse"))))

(deftest text-blank-is-missing
  (is (nil? (cells/parse-text "   ")))
  (is (= "nota" (cells/parse-text "  nota ")))
  (is (rejected? #(cells/parse-text (apply str (repeat 1001 "x"))))))
