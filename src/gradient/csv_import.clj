(ns gradient.csv-import
  "Lettura e validazione CSV senza persistenza o logging dei dati caricati."
  (:require [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException Charset CodingErrorAction)
           (java.text Normalizer Normalizer$Form)
           (java.util Base64 Locale)))

(def max-bytes (* 2 1024 1024))
(def max-rows 5000)
(def fields [:matricola :last-name :first-name :email :dsa :dis])
(def identity-fields [:matricola :last-name :first-name :email])
(def delimiters [\; \, \tab \|])

(defn normalized [value]
  (-> (Normalizer/normalize value Normalizer$Form/NFKC)
      str/trim
      (.toLowerCase Locale/ROOT)
      (str/split #"\s+")
      (->> (remove str/blank?) (str/join " "))))

(defn- length [^String s] (.codePointCount s 0 (count s)))

(defn issue [row field message] {:row row :field field :message message})

(defn- field-name [f] (str/replace (name f) "-" "_"))

;; ---------------------------------------------------------------------------
;; Lettura

(defn- decode [^bytes raw]
  (letfn [(strict [charset]
            (try (str (.decode (doto (.newDecoder (Charset/forName charset))
                                 (.onMalformedInput CodingErrorAction/REPORT)
                                 (.onUnmappableCharacter CodingErrorAction/REPORT))
                               (ByteBuffer/wrap raw)))
                 (catch CharacterCodingException _ nil)))]
    (if-let [text (strict "UTF-8")]
      (str/replace-first text #"^﻿" "")
      (strict "windows-1252"))))

(defn- physical-lines
  "Righe con il proprio terminatore (\\r\\n, \\n o \\r), come `io.StringIO(newline='')`."
  [content]
  (->> (re-seq #"([^\r\n]*)(\r\n|\n|\r|$)" content)
       (map (fn [[_ body terminator]] [body terminator]))
       (remove #(= ["" ""] %))))

(defn parse-records
  "Analizzatore CSV in modalità strict, con le regole del dialetto `excel` di Python.
  Restituisce `[[numero-riga-finale celle] ...]` oppure lancia ex-info con la riga."
  [content delimiter]
  (let [lines (physical-lines content)
        quote-char \"]
    (loop [lines lines
           line-num 0
           state :start-record
           field (StringBuilder.)
           cells []
           records []]
      (if-let [[[body terminator] & more] (seq lines)]
        (let [line-num (inc line-num)
              [state field cells]
              (reduce (fn [[state ^StringBuilder field cells] c]
                        (case state
                          (:start-record :start-field)
                          (cond (= c quote-char) [:quoted field cells]
                                (= c delimiter) [:start-field (StringBuilder.) (conj cells "")]
                                :else [:field (.append field c) cells])
                          :field
                          (if (= c delimiter)
                            [:start-field (StringBuilder.) (conj cells (str field))]
                            [:field (.append field c) cells])
                          :quoted
                          (if (= c quote-char) [:quote-in-quoted field cells] [:quoted (.append field c) cells])
                          :quote-in-quoted
                          (cond (= c quote-char) [:quoted (.append field c) cells]
                                (= c delimiter) [:start-field (StringBuilder.) (conj cells (str field))]
                                :else (throw (ex-info "Carattere inatteso dopo le virgolette." {:line line-num})))))
                      [state field cells]
                      body)]
          (cond
            (= state :quoted)
            (if (empty? terminator)
              (throw (ex-info "Fine dei dati inattesa." {:line line-num}))
              (recur more line-num :quoted (.append ^StringBuilder field ^String terminator) cells records))
            :else
            (let [cells (if (= state :start-record) cells (conj cells (str field)))]
              (recur more line-num :start-record (StringBuilder.) [] (conj records [line-num cells])))))
        (if (= state :quoted)
          (throw (ex-info "Fine dei dati inattesa." {:line line-num}))
          records)))))

(defn- sniff-delimiter
  "Semplificazione di `csv.Sniffer`: preferisce il separatore con lo stesso numero
  di occorrenze nel maggior numero di righe; altrimenti il più frequente."
  [content]
  (let [sample (subs content 0 (min 8192 (count content)))
        lines (remove str/blank? (str/split-lines sample))
        score (fn [d]
                (let [counts (remove zero? (map #(count (filter #{d} %)) lines))
                      modal (some->> (seq counts) frequencies (apply max-key val) val)]
                  [(or modal 0) (count (filter #{d} content))]))
        best (apply max-key (comp first score) (reverse delimiters))]
    (if (pos? (first (score best)))
      best
      (apply max-key #(count (filter #{%} content)) (reverse delimiters)))))

;; ---------------------------------------------------------------------------
;; Validazione

(defn parse-flag [value flag]
  (let [value (normalized value)]
    (cond
      (#{"" "0" "false" "no" "off"} value) false
      (#{"1" "true" "sì" "si" "yes" "x" "on"} value) true
      :else
      (let [tokens (set (re-seq #"(?U)\w+" value))]
        (if (and (seq tokens) (every? #{"dsa" "dis"} tokens))
          (contains? tokens (name flag))
          (throw (ex-info "Flag non riconosciuto: usare vuoto/no, sì/x oppure le sigle DSA e DIS." {})))))))

(defn- read-content
  "Decodifica e separatore, oppure `{:error messaggio}`."
  [content-base64]
  (let [raw (when (<= (count content-base64) (* 4 (quot (+ max-bytes 2) 3)))
              (try (.decode (Base64/getDecoder) ^String content-base64)
                   (catch IllegalArgumentException _ ::invalid)))]
    (cond
      (nil? raw) {:error "Il CSV deve essere al massimo di 2 MiB."}
      (= raw ::invalid) {:error "File non valido."}
      (or (zero? (alength ^bytes raw)) (> (alength ^bytes raw) max-bytes))
      {:error "Selezionare un CSV non vuoto, al massimo di 2 MiB."}
      :else
      (let [content (decode raw)]
        (cond
          (nil? content) {:error "Codifica del file non riconosciuta. Usare UTF-8 o Windows-1252."}
          (str/includes? content "\u0000") {:error "Il file contiene dati binari; selezionare un CSV."}
          :else
          (let [[first-line] (str/split content #"\r\n|\n|\r" 2)
                directive (str/trim first-line)]
            (if (str/starts-with? (str/lower-case directive) "sep=")
              (let [d (subs directive 4)]
                (if (and (= 1 (count d)) (some #{(first d)} delimiters))
                  {:delimiter (first d) :offset 1
                   :content (str/replace-first content #"^[^\r\n]*(\r\n|\n|\r)?" "")}
                  {:error "Separatore CSV non supportato."}))
              {:delimiter (sniff-delimiter content) :offset 0 :content content})))))))

(defn- guess-mapping [headers records]
  (let [names (mapv normalized headers)
        find (fn [& aliases] (first (keep-indexed (fn [i n] (when (some #{n} aliases) i)) names)))
        find-flag (fn [flag]
                    (or (find (name flag))
                        (some (fn [alias]
                                (when-let [index (find alias)]
                                  (when (some (fn [[_ row]]
                                                (and (< index (count row))
                                                     (re-find (re-pattern (str "(?iU)\\b" (name flag) "\\b")) (row index))))
                                              records)
                                    index)))
                              ["documentazione studente" "note di sistema"])
                        (find "documentazione studente")))]
    {:matricola (find "matricola")
     :last-name (find "cognome" "last name")
     :first-name (find "nome" "first name")
     :email (find "email" "e-mail")
     :dsa (find-flag :dsa)
     :dis (find-flag :dis)}))

(defn- mapping-errors [mapping headers]
  (concat
   (for [f fields
         :let [index (get mapping f)]
         :when (and (some? index) (not (and (int? index) (< -1 index (count headers)))))]
     (issue nil (field-name f) "Colonna selezionata non valida."))
   (when-not (some #(some? (get mapping %)) [:matricola :last-name :first-name])
     [(issue nil "mapping" "Associare almeno matricola, cognome o nome.")])
   (let [used (keep #(get mapping %) identity-fields)]
     (when (not= (count used) (count (distinct used)))
       [(issue nil "mapping" "Matricola, nome, cognome ed email devono usare colonne distinte.")]))))

(defn- validate-row [mapping seen [number row]]
  (let [values (into {} (for [f fields]
                          [f (if-let [i (get mapping f)] (str/trim (row i)) "")]))
        flag (fn [f] (try [(parse-flag (values f) f) nil]
                          (catch clojure.lang.ExceptionInfo e [false (issue number (field-name f) (ex-message e))])))
        [dsa dsa-error] (flag :dsa)
        [dis dis-error] (flag :dis)
        matricola (values :matricola)]
    {:errors (concat
              (for [f identity-fields
                    :let [maximum (case f :matricola 64 :email 320 200)]
                    :when (> (length (values f)) maximum)]
                (issue number (field-name f) (str "Inserire al massimo " maximum " caratteri.")))
              (when (every? #(str/blank? (values %)) [:matricola :last-name :first-name])
                [(issue number "student" "Riga senza matricola, nome o cognome.")])
              (when-let [previous (and (seq matricola) (seen matricola))]
                [(issue number "matricola" (str "Matricola duplicata rispetto alla riga " previous "."))])
              (keep identity [dsa-error dis-error]))
     :warnings (when (empty? matricola)
                 [(issue number "matricola" "Matricola mancante: la riga resterà indipendente dalle altre tabelle.")])
     :student {:row-number number
               :matricola (not-empty matricola)
               :last-name (values :last-name)
               :first-name (values :first-name)
               :email (not-empty (values :email))
               :dsa dsa
               :dis dis}}))

(def empty-preview
  {:headers [] :mapping (zipmap fields (repeat nil)) :rows [] :errors [] :warnings []
   :delimiter ";" :header-row 0 :total-rows 0})

(defn preview
  "Analizza tutto il file. `mapping` (indici di colonna) sostituisce l'associazione automatica."
  ([content-base64] (preview content-base64 nil))
  ([content-base64 mapping]
   (let [{:keys [error delimiter offset content]} (read-content content-base64)
         fail (fn [result message] (update result :errors conj (issue nil "file" message)))
         result (cond-> empty-preview delimiter (assoc :delimiter (str delimiter)))]
     (if error
       (fail result error)
       (let [parsed (try (->> (parse-records content delimiter)
                              (keep (fn [[line cells]]
                                      (when (some (complement str/blank?) cells)
                                        [(+ line offset) cells])))
                              (take (+ max-rows 102))
                              vec)
                         (catch clojure.lang.ExceptionInfo e
                           {:error (str "CSV non valido in prossimità della riga " (+ offset (:line (ex-data e))) ".")}))]
         (cond
           (:error parsed) (fail result (:error parsed))
           (> (count parsed) (+ max-rows 101)) (fail result "Il CSV contiene troppe righe (massimo 5000 studenti).")
           (empty? parsed) (fail result "Il CSV non contiene intestazioni.")
           :else
           (let [header-index (or (first (keep-indexed
                                          (fn [i [_ row]]
                                            (let [names (set (map normalized row))]
                                              (when (or (names "matricola") (every? names ["nome" "cognome"])) i)))
                                          (take 100 parsed)))
                                  0)
                 [header-row headers] (parsed header-index)
                 data (subvec parsed (inc header-index))
                 result (assoc result :header-row header-row :headers headers)]
             (if (> (count headers) 100)
               (fail result "Sono supportate al massimo 100 colonne.")
               (let [mapping (merge (zipmap fields (repeat nil)) (or mapping (guess-mapping headers data)))
                     result (assoc result :mapping mapping)
                     errors (vec (mapping-errors mapping headers))]
                 (cond
                   (seq errors) (assoc result :errors errors)
                   (empty? data) (fail result "Il CSV non contiene studenti.")
                   (> (count data) max-rows) (fail (assoc result :total-rows (count data)) "Sono supportati al massimo 5000 studenti.")
                   :else
                   (loop [[record & more] data, seen {}, result (assoc result :total-rows (count data))]
                     (if-let [[number row] record]
                       (if (not= (count row) (count headers))
                         (recur more seen (update result :errors conj (issue number "file" "Numero di celle diverso dalle intestazioni.")))
                         (let [{:keys [errors warnings student]} (validate-row mapping seen record)]
                           (recur more
                                  (cond-> seen (:matricola student) (assoc (:matricola student) number))
                                  (-> result
                                      (update :errors into errors)
                                      (update :warnings into warnings)
                                      (update :rows conj student)))))
                       result))))))))))))
