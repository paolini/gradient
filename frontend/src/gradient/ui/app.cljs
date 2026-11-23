(ns gradient.ui.app
  "Pagine dei corsi, del corso e della tabella, con routing sull'hash dell'URL."
  (:require [clojure.string :as str]
            [gradient.ui.api :as api]
            [gradient.ui.common :refer [course-details course-href go! load-list table-details table-href]]
            [gradient.ui.components :refer [dialog load-error not-found]]
            [gradient.ui.csv-import :refer [csv-import]]
            [gradient.ui.table :refer [table-page]]
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

(defonce route (r/atom (parse-route (.-hash js/location))))
(defonce _listener
  (js/window.addEventListener "hashchange" (fn [] (reset! route (parse-route (.-hash js/location))) (js/window.scrollTo 0 0))))

;; ---------------------------------------------------------------------------
;; Moduli

(defn- default-year []
  (let [now (js/Date.)
        year (- (.getFullYear now) (if (< (.getMonth now) 8) 1 0))]
    (str year "/" (inc year))))

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

(defn app []
  (let [{:keys [page course-id table-id]} @route]
    [:main.app
     (case page
       :home [home-page]
       :course ^{:key course-id} [course-page course-id]
       :table ^{:key table-id} [table-page course-id table-id])]))
