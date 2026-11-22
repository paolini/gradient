(ns gradient.ui.csv-import
  "Importazione degli iscritti da CSV: anteprima, associazione colonne, salvataggio."
  (:require [clojure.string :as str]
            [gradient.ui.api :as api]
            [reagent.core :as r]))

(def fields [[:matricola "Matricola"] [:lastName "Cognome"] [:firstName "Nome"]
             [:email "Email"] [:dsa "DSA"] [:dis "DIS"]])

(defn- issues [items title]
  (when (seq items)
    [:div.csv-issues
     [:h3 title " (" (count items) ")"]
     [:ul (for [[i {:keys [row message]}] (map-indexed vector (take 50 items))]
            ^{:key i} [:li (when row (str "Riga " row ": ")) message])]
     (when (> (count items) 50) [:p "Mostrati i primi 50 messaggi."])]))

(defn- base64 [file]
  (-> (.arrayBuffer file)
      (.then (fn [buffer]
               (let [bytes (js/Uint8Array. buffer)
                     chunks (for [start (range 0 (.-length bytes) 8192)]
                              (.apply js/String.fromCharCode nil (.subarray bytes start (+ start 8192))))]
                 (js/btoa (apply str chunks)))))))

(defn- preview-table [{:keys [rows totalRows]}]
  [:div.table-scroll
   [:table
    [:caption "Anteprima" (when (> totalRows 100) " dei primi 100 studenti")]
    [:thead [:tr (for [[_ label] fields] ^{:key label} [:th label])]]
    [:tbody
     (for [[i row] (map-indexed vector rows)]
       ^{:key i}
       [:tr [:td (or (not-empty (:matricola row)) "—")] [:td (:lastName row)] [:td (:firstName row)]
        [:td (:email row)] [:td (when (:dsa row) "Sì")] [:td (when (:dis row) "Sì")]])]]])

(defn csv-import [{:keys [course-id on-busy on-created]}]
  (let [state (r/atom {:content "" :filename "" :preview nil :error "" :issues [] :busy false
                       :name "" :date "" :location ""})
        version (atom 0)
        busy! (fn [value] (swap! state assoc :busy value) (on-busy value))
        inspect (fn [content mapping]
                  (-> (api/graphql :preview-csv {:input (cond-> {:contentBase64 content} mapping (assoc :mapping mapping))})
                      (.then :previewCsv)))
        upload (fn [file]
                 (let [current (swap! version inc)]
                   (swap! state assoc :preview nil :content "" :issues [] :error "" :filename (if file (.-name file) ""))
                   (cond
                     (nil? file) nil
                     (> (.-size file) (* 2 1024 1024)) (swap! state assoc :error "Il CSV deve essere al massimo di 2 MiB.")
                     :else
                     (do (busy! true)
                         (-> (base64 file)
                             (.then (fn [encoded]
                                      (.then (inspect encoded nil)
                                             (fn [result]
                                               (when (= current @version)
                                                 (swap! state assoc :content encoded :preview result
                                                        :name (str/replace (.-name file) #"(?i)\.csv$" "")))))))
                             (.catch (fn [e] (when (= current @version)
                                               (swap! state assoc :error (api/message e "Lettura del CSV non riuscita.")))))
                             (.finally (fn [] (when (= current @version) (busy! false)))))))))
        remap (fn [field index]
                (when-let [preview (:preview @state)]
                  (let [mapping (assoc (:mapping preview) field index)]
                    (busy! true)
                    ;; L'anteprima precedente viene invalidata subito: un errore non lascia attiva l'importazione.
                    (swap! state assoc :error "" :issues []
                           :preview (assoc preview :mapping mapping
                                           :errors [{:row nil :field "mapping" :message "Aggiornare l’anteprima prima di importare."}]))
                    (-> (inspect (:content @state) mapping)
                        (.then #(swap! state assoc :preview %))
                        (.catch #(swap! state assoc :error (api/message % "Anteprima non disponibile.")))
                        (.finally #(busy! false))))))
        save (fn [e]
               (.preventDefault e)
               (let [{:keys [preview content name date location]} @state]
                 (when (and preview (empty? (:errors preview)) (pos? (:totalRows preview)))
                   (busy! true)
                   (swap! state assoc :error "" :issues [])
                   (-> (api/graphql :import-csv {:input {:table {:courseId course-id :name name
                                                                 :date (not-empty date) :location (not-empty location)}
                                                         :file {:contentBase64 content :mapping (:mapping preview)}}})
                       (.then (fn [data]
                                (let [{:keys [table importedCount errors]} (:importCsvTable data)]
                                  (cond
                                    (seq errors) (swap! state assoc :issues errors)
                                    (nil? table) (throw (js/Error. "Importazione non completata."))
                                    :else (on-created table importedCount)))))
                       (.catch #(swap! state assoc :error (api/message % "Importazione non riuscita.")))
                       (.finally #(busy! false))))))]
    (fn [_]
      (let [{:keys [preview error busy filename name date location] :as s} @state
            input (fn [k] {:value (k s) :on-change #(swap! state assoc k (.. % -target -value))})]
        [:div.csv-import
         [:p "Carica gli iscritti scaricati dal sito di ateneo, verifica le colonne e crea una nuova tabella nel corso."]
         (when (seq error) [:p.error {:role "alert"} error])
         [:fieldset {:disabled busy}
          [:label "File CSV degli iscritti"
           [:input {:key (if (seq filename) filename "empty") :type "file" :accept ".csv,text/csv"
                    :on-change #(upload (aget (.. % -target -files) 0))}]]]
         (when busy [:p {:role "status"} (if preview "Aggiornamento…" "Lettura del CSV…")])
         (when preview
           [:<>
            [:p filename " · " (:totalRows preview) " studenti · intestazioni alla riga " (:headerRow preview) "."]
            [:p "Vengono importate solo le colonne associate qui sotto. Le altre colonne, le note e i dati introduttivi non vengono salvati. Le righe non vengono filtrate in base a «Cancellazioni»."]
            [:fieldset.csv-mapping {:disabled busy}
             [:legend "Associazione delle colonne"]
             (for [[field label] fields]
               ^{:key field}
               [:label label
                [:select {:value (str (get-in preview [:mapping field]))
                          :on-change #(let [v (.. % -target -value)] (remap field (when (seq v) (js/Number v))))}
                 [:option {:value ""} "Non importare"]
                 (for [[i header] (map-indexed vector (:headers preview))]
                   ^{:key i} [:option {:value i} (inc i) ". " (if (seq header) header "(senza titolo)")])]])]
            [:p "DSA e DIS possono leggere «Documentazione studente», «Note di sistema» o colonne distinte. Sono riconosciuti vuoto/no, sì/x, 0/1 e le sigle DSA/DIS; altri valori richiedono una correzione o una diversa associazione."]
            [issues (:errors preview) "Errori da correggere"]
            [issues (:warnings preview) "Avvisi"]
            [issues (:issues s) "Importazione non completata"]
            (when (seq (:rows preview)) [preview-table preview])
            [:form {:on-submit save}
             [:fieldset {:disabled (or busy (seq (:errors preview)) (zero? (:totalRows preview)))}
              [:legend "Informazioni della nuova tabella"]
              [:label "Nome della tabella da importare" [:input (merge (input :name) {:required true :max-length 200})]]
              [:label "Data della tabella importata (facoltativa)"
               [:input (merge (input :date) {:type "date" :min "1000-01-01" :max "9999-12-31"})]]
              [:label "Luogo della tabella importata (facoltativo)" [:input (merge (input :location) {:max-length 200})]]
              [:button "Crea tabella e importa " (:totalRows preview) " studenti"]]]])]))))
