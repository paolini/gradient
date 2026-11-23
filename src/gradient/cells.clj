(ns gradient.cells
  "Interpretazione e formattazione dei valori delle celle. Funzioni pure, senza database."
  (:require [clojure.string :as str])
  (:import (java.math BigDecimal)
           (java.util Locale)))

;; Tipi delle colonne. Le colonne anagrafiche leggono e scrivono i campi della riga.
(def builtin-kinds [:matricola :last-name :first-name :email :dsa :dis])
(def builtin-names {:matricola "Matricola" :last-name "Cognome" :first-name "Nome"
                    :email "Email" :dsa "DSA" :dis "DIS"})
(def custom-kinds #{:flag :grade :text})
(def modifiers ["+" "-" "L"])
(def text-max 1000)
(def grade-limit 1000000M)
(def grade-decimals 4)

(def true-words #{"sì" "si" "s" "x" "1" "true" "y" "yes" "on"})
(def false-words #{"no" "n" "0" "false" "off"})

(defn cell-error [message]
  (ex-info message {::cell-error true}))

(defn cell-error? [error]
  (::cell-error (ex-data error)))

(defn name-key
  "Forma usata per confrontare i nomi senza distinguere maiuscole e minuscole."
  [^String s]
  (.toLowerCase s Locale/ROOT))

(defn- length [^String s] (.codePointCount s 0 (count s)))

(defn parse-decimal
  "Numero con virgola o punto decimale, al massimo quattro cifre decimali."
  ([value] (parse-decimal value "Il valore"))
  ([value what]
   (let [cleaned (str/replace (str/trim value) "," ".")]
     (when-not (re-matches #"-?[0-9]+(\.[0-9]+)?" cleaned)
       (throw (cell-error (str what " non è un numero valido."))))
     (let [number (BigDecimal. cleaned)]
       (when (>= (.compareTo (.abs number) ^BigDecimal grade-limit) 0)
         (throw (cell-error (str what " è troppo grande."))))
       (when (> (.scale number) grade-decimals)
         (throw (cell-error (str what " ammette al massimo " grade-decimals " cifre decimali."))))
       number))))

(defn format-decimal
  "Senza zeri finali e con la virgola decimale: 7.5000 diventa \"7,5\"."
  [^BigDecimal number]
  (str/replace (.toPlainString (.stripTrailingZeros number)) "." ","))

(defn format-grade [base modifier]
  (str (format-decimal base) modifier))

(defn format-flag [value]
  (if value "sì" "no"))

(def ^:private grade-pattern
  #"(?iU)(-?[0-9]+(?:[.,][0-9]+)?)\s*(\+|-|l|e\s+lode)?")

(defn- integral? [^BigDecimal number]
  (zero? (.signum (.remainder number BigDecimal/ONE))))

(defn parse-grade
  "Restituisce [valore-base modificatore], oppure nil per la cella vuota.
  `scale` è la configurazione della colonna: :maximum, :allow-above-maximum,
  :allow-negative e :modifiers."
  [value {:keys [maximum allow-above-maximum allow-negative modifiers]}]
  (let [value (str/trim value)]
    (when (seq value)
      (let [[_ number raw] (or (re-matches grade-pattern value)
                               (throw (cell-error "Voto non valido. Esempi: 7, 7,5, 7+, 8-, 30L.")))
            base (parse-decimal number "Il voto")
            raw (name-key (or raw ""))
            modifier (if (or (= raw "l") (str/starts-with? raw "e")) "L" raw)]
        (when (seq modifier)
          (when-not (some #{modifier} modifiers)
            (throw (cell-error (str "Il modificatore «" modifier "» non è ammesso in questa colonna."))))
          (when-not (integral? base)
            (throw (cell-error "Un modificatore non può seguire un voto decimale."))))
        (when (and (neg? base) (not allow-negative))
          (throw (cell-error "I valori negativi non sono ammessi in questa colonna.")))
        (when (and maximum (> base maximum) (not allow-above-maximum))
          (throw (cell-error (str "Il valore supera il massimo della colonna (" (format-decimal maximum) ")."))))
        [base modifier]))))

(defn grade-violation
  "Messaggio d'errore se un voto già memorizzato non rispetta la nuova configurazione, altrimenti nil."
  [base modifier scale]
  (try
    (parse-grade (str/replace (format-grade base modifier) "," ".") scale)
    nil
    (catch clojure.lang.ExceptionInfo error
      (if (cell-error? error) (ex-message error) (throw error)))))

(defn parse-flag
  "true, false o nil (vuoto). Con `allow-empty` falso il vuoto vale false, come per DSA e DIS."
  ([value] (parse-flag value true))
  ([value allow-empty]
   (let [word (name-key (str/trim value))]
     (cond
       (str/blank? word) (if allow-empty nil false)
       (true-words word) true
       (false-words word) false
       :else (throw (cell-error "Valore non valido. Usare sì/no (anche s/n, x, 1/0) o lasciare vuoto."))))))

(defn parse-text
  "Testo senza spazi iniziali e finali, oppure nil se vuoto."
  ([value] (parse-text value text-max))
  ([value maximum]
   (let [value (str/trim value)]
     (when (> (length value) maximum)
       (throw (cell-error (str "Inserire al massimo " maximum " caratteri."))))
     (not-empty value))))
