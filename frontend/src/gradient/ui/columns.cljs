(ns gradient.ui.columns
  "Colonne nell'interfaccia: etichette, testo da modificare e ordinamento delle righe."
  (:require [clojure.string :as str]))

(def kind-labels
  {"MATRICOLA" "Matricola" "LAST_NAME" "Cognome" "FIRST_NAME" "Nome" "EMAIL" "Email" "DSA" "DSA" "DIS" "DIS"
   "FLAG" "Flag" "GRADE" "Voto" "TEXT" "Testo"})

(defn builtin? [column]
  (not (#{"FLAG" "GRADE" "TEXT"} (:kind column))))

(defn find-cell [row column]
  (first (filter #(= (:id column) (:columnId %)) (:cells row))))

(defn edit-text
  "Testo proposto quando si inizia a modificare una cella."
  [row column]
  (case (:kind column)
    "MATRICOLA" (or (:matricola row) "")
    "LAST_NAME" (:lastName row)
    "FIRST_NAME" (:firstName row)
    "EMAIL" (or (:email row) "")
    "DSA" (if (:dsa row) "sì" "")
    "DIS" (if (:dis row) "sì" "")
    (or (:display (find-cell row column)) "")))

(def ^:private modifier-rank {"-" 0 "" 1 "+" 2 "L" 3})

(defn- text-key [value]
  (if (str/blank? value) {:missing true} {:text value}))

(defn- sort-key
  "Chiave di ordinamento: testo oppure numero (con il rango del modificatore), o mancante."
  [row column]
  (case (:kind column)
    "MATRICOLA" (text-key (:matricola row))
    "LAST_NAME" (text-key (:lastName row))
    "FIRST_NAME" (text-key (:firstName row))
    "EMAIL" (text-key (:email row))
    "DSA" {:number (if (:dsa row) 1 0)}
    "DIS" {:number (if (:dis row) 1 0)}
    (if-let [cell (find-cell row column)]
      (case (:kind column)
        "FLAG" {:number (if (:flag cell) 1 0)}
        "GRADE" {:number (js/Number (:gradeBase cell)) :rank (get modifier-rank (:gradeModifier cell) 1)}
        (text-key (:text cell)))
      {:missing true})))

(def ^:private collator (js/Intl.Collator. "it" #js {:numeric true :sensitivity "base"}))

(defn- compare-keys [a b]
  (cond
    (or (:missing a) (:missing b)) 0
    (and (:text a) (:text b)) (.compare collator (:text a) (:text b))
    :else (let [difference (- (or (:number a) 0) (or (:number b) 0))]
            (if (zero? difference) (- (or (:rank a) 0) (or (:rank b) 0)) difference))))

(defn sort-rows
  "Identificatori delle righe ordinate secondo la vista; i valori mancanti restano sempre in fondo."
  [rows row-sort columns]
  (let [by-id (into {} (map (juxt :id identity)) columns)
        criteria (keep (fn [{:keys [columnId descending]}]
                         (when-let [column (by-id columnId)] [column descending]))
                       row-sort)
        compare-rows (fn [a b]
                       (or (some (fn [[column descending]]
                                   (let [left (sort-key a column)
                                         right (sort-key b column)]
                                     (if (not= (boolean (:missing left)) (boolean (:missing right)))
                                       (if (:missing left) 1 -1)
                                       (let [result (compare-keys left right)]
                                         (when-not (zero? result) (if descending (- result) result))))))
                                 criteria)
                           0))]
    (mapv :id (sort compare-rows rows))))

(defn below-threshold?
  "Voto inferiore alla sufficienza della colonna."
  [cell column]
  (let [threshold (get-in column [:grade :passThreshold])]
    (boolean (and cell (:gradeBase cell) threshold
                  (< (js/Number (:gradeBase cell)) (js/Number (str/replace threshold "," ".")))))))
