(ns gradient.ui.app
  "Pagine dei corsi, del corso e della tabella, con routing sull'hash dell'URL."
  (:require [clojure.string :as str]
            [gradient.ui.api :as api]
            [gradient.ui.components :refer [dialog load-error not-found]]
            [gradient.ui.csv-import :refer [csv-import]]
            [reagent.core :as r]))

;; ---------------------------------------------------------------------------
;; Routing

(defn parse-route [hash]
  (let [parts (->> (str/split (str/replace hash #"^#/?" "") #"/") (remove str/blank?) (map js/decodeURIComponent) vec)]
    (cond
      (and (= "corsi" (get parts 0)) (get parts 1) (= "tabelle" (get parts 2)) (get parts 3))
      {:page :table :course-id (parts 1) :table-id (parts 3)}
      (and (= "corsi" (get parts 0)) (get parts 1)) {:page :course :course-id (parts 1)}
      :else {:page :home})))

(defn course-href [course-id] (str "#/corsi/" (js/encodeURIComponent course-id)))
(defn table-href [course-id table-id] (str (course-href course-id) "/tabelle/" (js/encodeURIComponent table-id)))
(defn go! [href] (set! (.-hash js/location) href))

(defonce route (r/atom (parse-route (.-hash js/location))))
(defonce _listener
  (js/window.addEventListener "hashchange" (fn [] (reset! route (parse-route (.-hash js/location))) (js/window.scrollTo 0 0))))

;; ---------------------------------------------------------------------------
;; Dati

(defn load-list
  "Carica un elenco nella ratom `state`; le risposte superate da un nuovo caricamento vengono ignorate."
  [state operation field variables]
  (let [token (js/Symbol)]
    (reset! state {:items [] :loading true :error "" :token token})
    (-> (api/graphql operation variables)
        (.then #(when (= token (:token @state)) (swap! state assoc :items (get % field) :loading false)))
        (.catch #(when (= token (:token @state)) (swap! state assoc :loading false :error (api/message % "Caricamento non riuscito.")))))
    state))

(defn- default-year []
  (let [now (js/Date.)
        year (- (.getFullYear now) (if (< (.getMonth now) 8) 1 0))]
    (str year "/" (inc year))))

(defn- format-date [value]
  (let [[year month day] (str/split value #"-")] (str day "/" month "/" year)))

(defn- details [& parts] (str/join " · " (remove str/blank? parts)))
(defn course-details [{:keys [degreeProgram academicYear]}] (details degreeProgram academicYear))
(defn table-details [{:keys [date location]}] (details (some-> date format-date) location))

;; ---------------------------------------------------------------------------
;; Moduli

(defn- form
  "Modulo di creazione: invia `submit` con i valori correnti e gestisce attesa ed errori."
  [{:keys [initial submit]} render]
  (let [values (r/atom initial)
        status (r/atom {:pending false :error ""})
        on-submit (fn [e]
                    (.preventDefault e)
                    (reset! status {:pending true :error ""})
                    (-> (submit @values)
                        (.catch #(reset! status {:pending false :error (api/message % "Salvataggio non riuscito.")}))))]
    (fn [_ render]
      (let [{:keys [pending error]} @status
            field (fn [k] {:value (k @values) :on-change #(swap! values assoc k (.. % -target -value))})]
        [:form {:on-submit on-submit}
         (when (seq error) [:p.error {:role "alert"} error])
         [:fieldset {:disabled pending} (render field)]]))))

(defn course-form []
  [form {:initial {:name "" :degree-program "" :year (default-year)}
         :submit (fn [{:keys [name degree-program year]}]
                   (-> (api/graphql :create-course {:input {:name name :academicYear year :degreeProgram (not-empty degree-program)}})
                       (.then #(let [{:keys [course]} (api/require-success (:createCourse %))]
                                 (go! (course-href (:id course)))))))}
   (fn [field]
     [:<>
      [:label "Nome del corso" [:input (merge (field :name) {:max-length 200 :required true :auto-focus true :placeholder "Analisi 1"})]]
      [:label "Corso di laurea (facoltativo)" [:input (merge (field :degree-program) {:max-length 200 :placeholder "Matematica"})]]
      [:label "Anno accademico" [:input (merge (field :year) {:required true :placeholder "2026/2027"})]]
      [:div.dialog-actions [:button "Crea corso"]]])])

(defn table-form [course-id]
  [form {:initial {:name "" :date "" :location ""}
         :submit (fn [{:keys [name date location]}]
                   (-> (api/graphql :create-table {:input {:courseId course-id :name name
                                                           :date (not-empty date) :location (not-empty location)}})
                       (.then #(let [{:keys [table]} (api/require-success (:createTable %))]
                                 (go! (table-href course-id (:id table)))))))}
   (fn [field]
     [:<>
      [:label "Nome della tabella" [:input (merge (field :name) {:required true :max-length 200 :auto-focus true :placeholder "Primo compitino"})]]
      [:label "Data (facoltativa)" [:input (merge (field :date) {:type "date" :min "1000-01-01" :max "9999-12-31"})]]
      [:label "Luogo (facoltativo)" [:input (merge (field :location) {:max-length 200 :placeholder "Aula A"})]]
      [:div.dialog-actions [:button "Crea tabella"]]])])

;; ---------------------------------------------------------------------------
;; Pagine

(defn home-page []
  (let [courses (load-list (r/atom nil) :courses :courses {})
        creating (r/atom false)]
    (fn []
      (let [{:keys [items loading error]} @courses]
        [:<>
         [:div.page-heading
          [:div [:h1 "I tuoi corsi"] [:p "Ogni corso è una specifica edizione dell'insegnamento."]]
          [:button {:type "button" :on-click #(reset! creating true)} "+ Nuovo corso"]]
         (when (seq error) [load-error error #(load-list courses :courses :courses {})])
         (cond
           loading [:p {:role "status"} "Caricamento…"]
           (and (empty? error) (empty? items))
           [:div.empty-state [:p "Non hai ancora creato corsi."]
            [:button {:type "button" :on-click #(reset! creating true)} "Crea il primo corso"]]
           :else
           [:ul.card-grid
            (for [course items]
              ^{:key (:id course)}
              [:li [:a.card {:href (course-href (:id course))}
                    [:strong (:name course)] [:span (course-details course)]]])])
         (when @creating
           [dialog {:title "Nuovo corso" :on-close #(reset! creating false)} [course-form]])]))))

(defn course-page [course-id]
  (let [courses (r/atom nil)
        tables (r/atom nil)
        reload! #(do (load-list courses :courses :courses {})
                     (load-list tables :tables :courseTables {:courseId course-id}))
        open (r/atom nil)
        busy (r/atom false)]
    (reload!)
    (fn [course-id]
      (let [course (first (filter #(= course-id (:id %)) (:items @courses)))
            error (some not-empty [(:error @courses) (:error @tables)])]
        (cond
          error [:<> [:a.back {:href "#/"} "‹ Corsi"] [load-error error reload!]]
          (:loading @courses) [:p {:role "status"} "Caricamento…"]
          (nil? course) [not-found "Corso non trovato."]
          :else
          [:<>
           [:a.back {:href "#/"} "‹ Corsi"]
           [:div.page-heading
            [:div [:h1 (:name course)] [:p (course-details course)]]
            [:div.actions
             [:button.secondary {:type "button" :on-click #(reset! open :csv)} "Importa da CSV"]
             [:button {:type "button" :on-click #(reset! open :table)} "+ Nuova tabella"]]]
           [:section
            [:h2 "Tabelle"]
            (cond
              (:loading @tables) [:p {:role "status"} "Caricamento…"]
              (empty? (:items @tables))
              [:p.muted-text "Nessuna tabella. Creane una vuota o importa gli iscritti da un CSV."]
              :else
              [:ul.link-list
               (for [table (:items @tables)]
                 ^{:key (:id table)}
                 [:li [:a {:href (table-href course-id (:id table))}
                       [:strong (:name table)] [:span (table-details table)] [:span {:aria-hidden true} "›"]]])])]
           (case @open
             :table [dialog {:title "Nuova tabella" :on-close #(reset! open nil)} [table-form course-id]]
             :csv [dialog {:title "Nuova tabella da CSV" :wide true :on-close #(when-not @busy (reset! open nil))}
                   [csv-import {:course-id course-id
                                :on-busy #(reset! busy %)
                                :on-created (fn [table _] (go! (table-href course-id (:id table))))}]]
             nil)])))))

(defn- new-student-row [table-id on-added]
  (let [values (r/atom {:matricola "" :lastName "" :firstName ""})
        status (r/atom {:pending false :error ""})
        first-input (atom nil)
        submit (fn [e]
                 (.preventDefault e)
                 (let [{:keys [matricola lastName firstName]} @values]
                   (when-not (every? str/blank? [matricola lastName firstName])
                     (reset! status {:pending true :error ""})
                     (-> (api/graphql :add-student {:input {:tableId table-id :matricola (not-empty matricola)
                                                            :firstName firstName :lastName lastName}})
                         (.then #(do (on-added (:row (api/require-success (:addStudent %))))
                                     (reset! values {:matricola "" :lastName "" :firstName ""})))
                         (.catch #(swap! status assoc :error (api/message % "Salvataggio non riuscito.")))
                         (.finally #(do (swap! status assoc :pending false)
                                        (js/setTimeout (fn [] (some-> @first-input .focus)))))))))]
    (fn [_ _]
      (let [{:keys [pending error]} @status
            cell (fn [k label placeholder max-length & [ref]]
                   [:td [:input {:ref ref :form "new-student" :aria-label (str label " del nuovo studente")
                                 :placeholder placeholder :value (k @values) :max-length max-length
                                 :auto-complete "off" :disabled pending
                                 :on-change #(swap! values assoc k (.. % -target -value))}]])]
        [:tfoot
         [:tr.new-row
          (cell :matricola "Matricola" "+ Matricola" 64 #(reset! first-input %))
          (cell :lastName "Cognome" "Cognome" 200)
          (cell :firstName "Nome" "Nome" 200)
          [:td.new-row-hint {:col-span 3}
           [:form#new-student {:on-submit submit}]
           [:button.secondary.small {:form "new-student" :disabled pending} "Aggiungi"] " " [:span "oppure Invio"]
           (when (seq error) [:span.error {:role "alert"} " " error])]]]))))

(defn table-page [course-id table-id]
  (let [courses (r/atom nil)
        tables (r/atom nil)
        rows (r/atom nil)
        added (r/atom [])
        reload! #(do (reset! added [])
                     (load-list courses :courses :courses {})
                     (load-list tables :tables :courseTables {:courseId course-id})
                     (load-list rows :rows :studentRows {:tableId table-id}))]
    (reload!)
    (fn [course-id table-id]
      (let [course (first (filter #(= course-id (:id %)) (:items @courses)))
            table (first (filter #(= table-id (:id %)) (:items @tables)))
            error (some not-empty (map :error [@courses @tables @rows]))
            all-rows (if (:loading @rows) [] (concat (:items @rows) @added))]
        (cond
          (and error (empty? (:items @rows)))
          [:<> [:a.back {:href (course-href course-id)} "‹ Corso"] [load-error error reload!]]
          (or (:loading @courses) (:loading @tables)) [:p {:role "status"} "Caricamento…"]
          (nil? course) [not-found "Corso non trovato."]
          (nil? table) [not-found "Tabella non trovata."]
          :else
          [:<>
           [:a.back {:href (course-href course-id)} "‹ " (:name course) " · " (:academicYear course)]
           [:div.page-heading
            [:div [:h1 (:name table)] (when-let [d (not-empty (table-details table))] [:p d])]
            [:span.count (when-not (:loading @rows) (str (count all-rows) " studenti"))]]
           [:div.table-scroll.sheet
            [:table
             [:thead [:tr (for [h ["Matricola" "Cognome" "Nome" "Email" "DSA" "DIS"]] ^{:key h} [:th h])]]
             [:tbody
              (for [row all-rows]
                ^{:key (:id row)}
                [:tr [:td (or (:matricola row) [:span.muted "Da inserire"])] [:td (:lastName row)] [:td (:firstName row)]
                 [:td (:email row)] [:td (when (:dsa row) "Sì")] [:td (when (:dis row) "Sì")]])
              (when (:loading @rows) [:tr [:td.empty {:col-span 6} "Caricamento…"]])]
             [new-student-row table-id #(swap! added conj %)]]]])))))

(defn app []
  (let [{:keys [page course-id table-id]} @route]
    [:main.app
     (case page
       :home [home-page]
       :course ^{:key course-id} [course-page course-id]
       :table ^{:key table-id} [table-page course-id table-id])]))
