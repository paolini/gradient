(ns gradient.csv-import-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [gradient.csv-import :as csv]
            [gradient.test-support :refer [encoded gql login! test-system]])
  (:import (java.nio.file Files Path)
           (java.util Base64)))

(deftest reviewed-unipi-example
  (let [data (.encodeToString (Base64/getEncoder) (Files/readAllBytes (Path/of "examples/unipi-iscritti.csv" (make-array String 0))))
        result (csv/preview data)]
    (is (= [] (:errors result)))
    (is (= 10 (:header-row result)))
    (is (= ";" (:delimiter result)))
    (is (= 14 (:total-rows result) (count (:rows result))))
    (is (= {:matricola 0 :last-name 1 :first-name 2 :email 3 :dsa 9 :dis 11} (:mapping result)))
    (is (every? #(str/starts-with? (:matricola %) "FITTIZIA") (:rows result)))
    (is (every? #(str/ends-with? (:email %) "@example.invalid") (:rows result)))
    (is (= 2 (count (filter :dsa (:rows result)))))
    (is (not-any? :dis (:rows result)))))

(deftest quoted-fields-multiline-and-windows-encoding
  (let [result (csv/preview (encoded "sep=;\r\nInformazioni;Prova\r\nMatricola;Cognome;Nome;Email;Documentazione studente\r\n00123;\"Dè Rossi; Bianchi\";Anna;;DSA/DIS\r\n;Verdi;\"Maria\r\nLuisa\";;\r\n" "windows-1252"))
        [first-row second-row] (:rows result)]
    (is (empty? (:errors result)))
    (is (= "00123" (:matricola first-row)))
    (is (= "Dè Rossi; Bianchi" (:last-name first-row)))
    (is (and (:dsa first-row) (:dis first-row)))
    (is (= "Maria\r\nLuisa" (:first-name second-row)))
    (is (= 6 (:row-number second-row)) "numero dell'ultima riga fisica, come csv.reader.line_num")
    (is (nil? (:matricola second-row)))
    (is (= 1 (count (:warnings result))))))

(deftest duplicate-matricola-blocks-import-but-missing-values-are-allowed
  (let [result (csv/preview (encoded "Matricola,Nome,Cognome\n00123,Ada,Rossi\n 00123 ,Luca,Verdi\n,Anna,Bianchi\n,Marco,Neri\n"))
        [error] (:errors result)]
    (is (= 1 (count (:errors result))))
    (is (= "matricola" (:field error)))
    (is (= 3 (:row error)))
    (is (str/includes? (:message error) "riga 2"))
    (is (= 2 (count (:warnings result))))))

(deftest mapping-and-explicit-independent-flags
  (let [result (csv/preview (encoded "ID;Family;Given;SupportA;SupportB\n0001;Rossi;Anna;sì;no\n0002;Verdi;Luca;;x\n")
                            {:matricola 0 :last-name 1 :first-name 2 :dsa 3 :dis 4})]
    (is (empty? (:errors result)))
    (is (= [[true false] [false true]] (map (juxt :dsa :dis) (:rows result))))
    (is (= "0001" (:matricola (first (:rows result)))))))

(deftest invalid-csv-returns-errors-without-echoing-data
  (are [value mapping] (seq (:errors (csv/preview value mapping)))
    (encoded "Matricola;Nome\n1;Anna;extra\n") nil
    (encoded "Matricola;Nome\n") nil
    (encoded "Matricola;Nome\n;\n") nil
    (encoded (str "Matricola;Nome\n1;" (apply str (repeat 201 "x")))) nil
    (encoded "Matricola;Nome;DSA\n1;Anna;sconosciuto") nil
    (encoded "Matricola;Nome\n1;Anna") {:matricola -1}
    (encoded "Matricola;Nome\n1;Anna") {:matricola 10}
    (encoded "Matricola;Nome\n1;Anna") {:matricola 0 :first-name 0}
    "invalid-base64!" nil
    (encoded "") nil
    (encoded (apply str (repeat (inc csv/max-bytes) "x"))) nil
    (encoded "Matricola;Nome\n1;\"Anna\n") nil
    (encoded "Matricola;Nome\n1;\"Anna\"x\n") nil))

(def preview-query
  "mutation($input: PreviewCsvInput!) { previewCsv(input: $input) { totalRows rows { matricola } errors { row field } } }")

(deftest preview-and-import-revalidate-without-reaching-the-store
  (let [system (test-system)
        token (login! system)
        content (encoded "Matricola;Nome;Cognome\n1;Ada;Rossi\n1;Anna;Verdi\n")]
    (testing "l'anteprima segnala il duplicato"
      (let [{:keys [data errors]} (gql system token preview-query {:input {:contentBase64 content}})]
        (is (nil? errors))
        (is (= 2 (get-in data [:previewCsv :totalRows])))
        (is (= [{:row 3 :field "matricola"}] (get-in data [:previewCsv :errors])))))
    (testing "il salvataggio rivalida il file anche con un corso inesistente"
      (let [{:keys [data errors]}
            (gql system token
                 "mutation($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id } importedCount errors { row field } } }"
                 {:input {:table {:courseId "00000000-0000-0000-0000-000000000001" :name "Compitino"}
                          :file {:contentBase64 content}}})]
        (is (nil? errors))
        (is (= {:table nil :importedCount 0 :errors [{:row 3 :field "matricola"}]} (:importCsvTable data)))))))

(deftest preview-is-limited-but-all-rows-are-validated
  (let [system (test-system)
        content (str "Matricola;Nome\n" (str/join "\n" (map #(str % ";Anna") (range 101))) "\n0;Duplicato")
        preview (get-in (gql system (login! system) preview-query {:input {:contentBase64 (encoded content)}})
                        [:data :previewCsv])]
    (is (= 102 (:totalRows preview)))
    (is (= 100 (count (:rows preview))))
    (is (= [{:row 103 :field "matricola"}] (:errors preview)))))
