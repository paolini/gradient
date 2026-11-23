(ns gradient.ui.table
  "Pagina della tabella: griglia modificabile, colonne e viste personali."
  (:require [clojure.string :as str]
            [gradient.ui.api :as api]
            [gradient.ui.column-dialog :refer [column-dialog]]
            [gradient.ui.columns :refer [builtin? kind-labels sort-rows]]
            [gradient.ui.common :refer [course-href load-list table-details]]
            [gradient.ui.components :refer [dialog load-error menu not-found]]
            [gradient.ui.grid :refer [grid]]
            [reagent.core :as r]))

;; ---------------------------------------------------------------------------
;; Finestre

(defn- name-dialog
  "Chiede un nome; `on-submit` restituisce una promessa e la finestra si chiude se riesce."
  [{:keys [initial]}]
  (let [state (r/atom {:name initial :pending false :error ""})]
    (fn [{:keys [title action on-submit on-close]}]
      (let [{:keys [name pending error]} @state]
        [dialog {:title title :on-close on-close}
         [:form {:on-submit (fn [e]
                              (.preventDefault e)
                              (swap! state assoc :pending true :error "")
                              (-> (on-submit name)
                                  (.then (fn [_] (on-close)))
                                  (.catch #(swap! state assoc :error (api/message % "Salvataggio non riuscito.")))
                                  (.finally #(swap! state assoc :pending false))))}
          (when (seq error) [:p.error {:role "alert"} error])
          [:fieldset {:disabled pending}
           [:label "Nome" [:input {:value name :on-change #(swap! state assoc :name (.. % -target -value))
                                   :required true :max-length 100 :auto-focus true}]]
           [:div.dialog-actions
            [:button.secondary {:type "button" :on-click on-close} "Annulla"]
            [:button action]]]]]))))

(defn- columns-dialog
  "Visibilità e ordine delle colonne nella vista corrente."
  [{:keys [columns view on-change on-close]}]
  (let [by-id (into {} (map (juxt :id identity)) columns)
        order (filterv by-id (:columnOrder view))
        hidden (set (:hiddenColumns view))
        swap-with (fn [index delta]
                    (on-change {:columnOrder (assoc order index (order (+ index delta)) (+ index delta) (order index))}))
        toggle (fn [id]
                 (on-change {:hiddenColumns (if (hidden id)
                                              (filterv #(not= id %) (:hiddenColumns view))
                                              (conj (vec (:hiddenColumns view)) id))}))]
    [dialog {:title "Colonne della vista" :on-close on-close}
     [:p "Visibilità e ordine valgono solo per la vista «" (:name view) "»."]
     [:ul.column-list
      (for [[index id] (map-indexed vector order)
            :let [column (by-id id)]]
        ^{:key id}
        [:li
         [:label.check [:input {:type "checkbox" :checked (not (hidden id)) :on-change #(toggle id)}]
          " " (:name column) " " [:small (kind-labels (:kind column))]]
         [:span.order-buttons
          [:button.icon-button {:type "button" :aria-label (str "Sposta " (:name column) " su")
                                :disabled (zero? index) :on-click #(swap-with index -1)} "↑"]
          [:button.icon-button {:type "button" :aria-label (str "Sposta " (:name column) " giù")
                                :disabled (= index (dec (count order))) :on-click #(swap-with index 1)} "↓"]]])]
     [:div.dialog-actions [:button {:type "button" :on-click on-close} "Fatto"]]]))

;; ---------------------------------------------------------------------------
;; Nuovo studente: i campi compaiono sotto le colonne anagrafiche visibili.

(def ^:private empty-draft {:matricola "" :lastName "" :firstName ""})
(def ^:private draft-fields {"MATRICOLA" [:matricola "+ Matricola"] "LAST_NAME" [:lastName "Cognome"] "FIRST_NAME" [:firstName "Nome"]})

(defn- new-student-field [column draft]
  (when-let [[k placeholder] (draft-fields (:kind column))]
    [:input {:form "new-student" :data-new-student (name k) :aria-label (str (:name column) " del nuovo studente")
             :placeholder placeholder :value (k @draft) :max-length (if (= k :matricola) 64 200) :auto-complete "off"
             :on-change #(swap! draft assoc k (.. % -target -value))}]))

(defn- new-student [_]
  (let [status (r/atom {:pending false :error ""})]
    (fn [{:keys [table-id draft on-added has-name-columns]}]
      (let [{:keys [pending error]} @status
            submit (fn [e]
                     (.preventDefault e)
                     (let [{:keys [matricola lastName firstName]} @draft]
                       (when-not (every? str/blank? [matricola lastName firstName])
                         (reset! status {:pending true :error ""})
                         (-> (api/graphql :add-student {:input {:tableId table-id :matricola (not-empty matricola)
                                                                :lastName lastName :firstName firstName}})
                             (.then #(do (some-> (:row (api/require-success (:addStudent %))) on-added)
                                         (reset! draft empty-draft)))
                             (.catch #(swap! status assoc :error (api/message % "Salvataggio non riuscito.")))
                             (.finally #(do (swap! status assoc :pending false)
                                            (js/setTimeout (fn [] (some-> (js/document.querySelector "[data-new-student]") .focus)))))))))]
        [:form#new-student.new-student {:on-submit submit}
         (when (seq error) [:p.error {:role "alert"} error])
         (if has-name-columns
           [:button.secondary.small {:disabled pending} "Aggiungi studente"]
           [:p.hint "Mostra matricola, cognome o nome per aggiungere studenti."])]))))

;; ---------------------------------------------------------------------------
;; Viste

(defn- with-active-view
  "Sostituisce la vista salvata (anche la predefinita non memorizzata) e la rende l'unica attiva."
  [views saved]
  (->> views
       (remove #(or (nil? (:id %)) (= (:id %) (:id saved))))
       (map #(assoc % :active false))
       (cons saved)
       (sort-by :name #(.localeCompare %1 %2 "it"))
       vec))

(defn- remove-column [data column-id]
  (-> data
      (update :tableColumns (fn [columns] (filterv #(not= column-id (:id %)) columns)))
      (update :studentRows (fn [rows] (mapv (fn [row] (update row :cells (fn [cells] (filterv #(not= column-id (:columnId %)) cells)))) rows)))
      (update :tableViews (fn [views] (mapv (fn [view] (-> view
                                                           (update :columnOrder (fn [ids] (filterv #(not= column-id %) ids)))
                                                           (update :hiddenColumns (fn [ids] (filterv #(not= column-id %) ids)))
                                                           (update :rowSort (fn [items] (filterv #(not= column-id (:columnId %)) items)))))
                                            views)))))

;; ---------------------------------------------------------------------------
;; Pagina

(defn table-page [course-id table-id]
  (let [courses (r/atom nil)
        tables (r/atom nil)
        data (r/atom nil)                       ; {:tableColumns :studentRows :tableViews}
        failure (r/atom "")
        row-order (r/atom [])
        order-basis (atom nil)                  ; [id-vista ordinamento] usati per row-order
        error (r/atom "")
        open-dialog (r/atom nil)
        draft (r/atom empty-draft)
        save-queue (atom (js/Promise.resolve))
        view-id (atom nil)
        current-view (fn []
                       (let [views (:tableViews @data)]
                         (or (first (filter :active views)) (first views))))
        ;; L'ordine delle righe si ricalcola al caricamento e quando cambiano vista o
        ;; ordinamento, non a ogni modifica: la riga che si sta compilando non deve spostarsi.
        sync-order! (fn []
                      (when-let [view (current-view)]
                        (let [basis [(:id view) (:rowSort view)]]
                          (when (not= basis @order-basis)
                            (reset! order-basis basis)
                            (reset! row-order (sort-rows (:studentRows @data) (:rowSort view) (:tableColumns @data)))))))
        change-data! (fn [f & args]
                       (apply swap! data f args)
                       (sync-order!))
        reload! (fn []
                  (reset! data nil)
                  (reset! failure "")
                  (reset! order-basis nil)
                  (load-list courses :courses :courses {})
                  (load-list tables :tables :courseTables {:courseId course-id})
                  (-> (api/graphql :table-data {:tableId table-id})
                      (.then #(do (reset! data %) (sync-order!)))
                      (.catch #(reset! failure (api/message % "Caricamento non riuscito.")))))
        show-saved-view! (fn [saved] (change-data! update :tableViews with-active-view saved))
        save-view (fn [view-id view-name settings]
                    (-> (api/graphql :save-view {:input (merge {:tableId table-id :viewId view-id :name view-name}
                                                               (select-keys settings [:columnOrder :hiddenColumns :rowSort]))})
                        (.then #(:view (api/require-success (:saveTableView %))))))
        ;; Le modifiche alla vista si vedono subito e si salvano una richiesta alla volta.
        update-view! (fn [settings]
                       (when-let [view (current-view)]
                         (let [next-view (merge view settings)]
                           (change-data! update :tableViews (fn [views] (mapv #(if (identical? % view) next-view %) views)))
                           (swap! save-queue
                                  (fn [queue]
                                    (.then queue
                                           (fn []
                                             (-> (save-view @view-id (:name next-view) next-view)
                                                 (.then (fn [saved]
                                                          (when saved
                                                            (reset! view-id (:id saved))
                                                            (show-saved-view! saved))))
                                                 (.catch #(reset! error (api/message % "Impossibile salvare la vista.")))))))))))
        create-view (fn [view-name]
                      (-> (save-view nil view-name (current-view))
                          (.then #(some-> % show-saved-view!))))
        rename-view (fn [view-name]
                      (let [view (current-view)]
                        (-> (save-view (:id view) view-name view)
                            (.then #(some-> % show-saved-view!)))))
        select-view (fn [id]
                      (reset! error "")
                      (-> (api/graphql :select-view {:viewId id})
                          (.then #(some-> (:view (api/require-success (:selectTableView %))) show-saved-view!))
                          (.catch #(reset! error (api/message % "Impossibile aprire la vista.")))))
        delete-view (fn []
                      (let [view (current-view)]
                        (when (and (:id view)
                                   (js/confirm (str "Eliminare la vista «" (:name view) "»? I dati della tabella non vengono modificati.")))
                          (reset! error "")
                          (-> (api/graphql :delete-view {:viewId (:id view)})
                              (.then #(when-let [views (:views (api/require-success (:deleteTableView %)))]
                                        (change-data! assoc :tableViews views)))
                              (.catch #(reset! error (api/message % "Impossibile eliminare la vista.")))))))
        sort-by-column (fn [column]
                         (let [first-sort (first (:rowSort (current-view)))]
                           (update-view! {:rowSort [{:columnId (:id column)
                                                     :descending (and (= (:id column) (:columnId first-sort)) (not (:descending first-sort)))}]})))
        move-column (fn [visible column delta]
                      ;; Lo spostamento avviene tra le colonne visibili; quelle nascoste restano al loro posto.
                      (let [visible-ids (mapv :id visible)
                            index (.indexOf visible-ids (:id column))
                            target (get visible-ids (+ index delta))]
                        (when target
                          (let [order (vec (:columnOrder (current-view)))
                                from (.indexOf order (:id column))
                                to (.indexOf order target)]
                            (update-view! {:columnOrder (assoc order from target to (:id column))})))))
        delete-column (fn [column]
                        (when (js/confirm (str "Eliminare la colonna «" (:name column) "» e tutti i suoi valori? L'operazione non può essere annullata."))
                          (reset! error "")
                          (-> (api/graphql :delete-column {:columnId (:id column)})
                              (.then #(do (api/require-success (:deleteColumn %))
                                          (change-data! remove-column (:id column))))
                              (.catch #(reset! error (api/message % "Impossibile eliminare la colonna."))))))
        column-saved (fn [column]
                       (change-data! (fn [d]
                                       (if (some #(= (:id column) (:id %)) (:tableColumns d))
                                         (update d :tableColumns (fn [columns] (mapv #(if (= (:id column) (:id %)) column %) columns)))
                                         ;; Le nuove colonne compaiono in fondo a tutte le viste, come sul server.
                                         (-> d
                                             (update :tableColumns conj column)
                                             (update :tableViews (fn [views] (mapv #(update % :columnOrder conj (:id column)) views)))))))
                       (reset! open-dialog nil))
        commit-cell (fn [row column value]
                      (-> (api/graphql :set-cell {:input {:rowId (:id row) :columnId (:id column) :value value}})
                          (.then (fn [result]
                                   (when-let [updated (:row (api/require-success (:setCell result)))]
                                     (swap! data update :studentRows (fn [rows] (mapv #(if (= (:id updated) (:id %)) updated %) rows))))
                                   nil))
                          (.catch #(api/message % "Salvataggio non riuscito."))))
        close-dialog #(reset! open-dialog nil)]
    (reload!)
    (fn [course-id table-id]
      (let [course (first (filter #(= course-id (:id %)) (:items @courses)))
            table (first (filter #(= table-id (:id %)) (:items @tables)))
            load-failure (some not-empty [@failure (:error @courses) (:error @tables)])
            {:keys [tableColumns studentRows tableViews] :as d} @data
            view (current-view)
            _ (reset! view-id (:id view))
            by-id (into {} (map (juxt :id identity)) tableColumns)
            hidden (set (:hiddenColumns view))
            visible (into [] (comp (remove hidden) (keep by-id)) (:columnOrder view))
            rows-by-id (into {} (map (juxt :id identity)) studentRows)
            ordered (into (filterv some? (map rows-by-id @row-order))
                          (remove (comp (set @row-order) :id))
                          studentRows)
            first-sort (first (:rowSort view))]
        (cond
          load-failure [:<> [:a.back {:href (course-href course-id)} "‹ Corso"] [load-error load-failure reload!]]
          (or (:loading @courses) (:loading @tables)) [:p {:role "status"} "Caricamento…"]
          (nil? course) [not-found "Corso non trovato."]
          (nil? table) [not-found "Tabella non trovata."]
          :else
          [:<>
           [:a.back {:href (course-href course-id)} "‹ " (:name course) " · " (:academicYear course)]
           [:div.page-heading
            [:div [:h1 (:name table)] (when-let [details (not-empty (table-details table))] [:p details])]
            [:span.count (when d (str (count studentRows) " studenti"))]]
           (when (seq @error)
             [:div.error {:role "alert"} @error " " [:button {:type "button" :on-click #(reset! error "")} "Chiudi"]])
           (if (or (nil? d) (nil? view))
             [:p {:role "status"} "Caricamento…"]
             [:<>
              [:div.toolbar
               [:label.inline "Vista"
                [:select {:value (or (:id view) "")
                          :on-change #(let [value (.. % -target -value)] (when (seq value) (select-view value)))}
                 (for [item tableViews]
                   ^{:key (or (:id item) "default")} [:option {:value (or (:id item) "")} (:name item)])]]
               [menu {:label "Vista ▾" :title "Gestisci viste"}
                (fn [close]
                  [:<>
                   [:button {:type "button" :role "menuitem" :on-click #(do (close) (reset! open-dialog {:type :new-view}))}
                    "Nuova vista (copia di questa)"]
                   [:button {:type "button" :role "menuitem" :on-click #(do (close) (reset! open-dialog {:type :rename-view}))}
                    "Rinomina vista"]
                   [:button {:type "button" :role "menuitem" :disabled (nil? (:id view)) :on-click #(do (close) (delete-view))}
                    "Elimina vista"]])]
               [:button.secondary {:type "button" :on-click #(reset! open-dialog {:type :columns})}
                "Colonne" (when (seq hidden) (str " (" (count hidden) " nascoste)"))]
               [:span.spacer]
               [:button {:type "button" :on-click #(reset! open-dialog {:type :add-column})} "+ Colonna"]]
              (if (seq visible)
                [grid {:columns visible
                       :rows ordered
                       :on-commit commit-cell
                       :header (fn [column]
                                 [:div.column-header
                                  [:button.sort-button {:type "button" :title "Ordina per questa colonna"
                                                        :on-click #(sort-by-column column)}
                                   (:name column)
                                   (when (= (:id column) (:columnId first-sort)) (if (:descending first-sort) " ▼" " ▲"))]
                                  [menu {:label "▾" :title (str "Opzioni della colonna " (:name column))}
                                   (fn [close]
                                     [:<>
                                      [:button {:type "button" :role "menuitem"
                                                :on-click #(do (close) (reset! open-dialog {:type :edit-column :column column}))}
                                       (if (= "GRADE" (:kind column)) "Rinomina e configura…" "Rinomina…")]
                                      [:button {:type "button" :role "menuitem" :on-click #(do (close) (move-column visible column -1))}
                                       "Sposta a sinistra"]
                                      [:button {:type "button" :role "menuitem" :on-click #(do (close) (move-column visible column 1))}
                                       "Sposta a destra"]
                                      [:button {:type "button" :role "menuitem"
                                                :on-click #(do (close) (update-view! {:hiddenColumns (conj (vec (:hiddenColumns view)) (:id column))}))}
                                       "Nascondi"]
                                      (when-not (builtin? column)
                                        [:button.danger {:type "button" :role "menuitem" :on-click #(do (close) (delete-column column))}
                                         "Elimina colonna…"])])]])
                       :footer (fn [column] [new-student-field column draft])}]
                [:div.empty-state
                 [:p "Tutte le colonne sono nascoste in questa vista."]
                 [:button {:type "button" :on-click #(reset! open-dialog {:type :columns})} "Mostra colonne"]])
              [new-student {:table-id table-id
                            :draft draft
                            :has-name-columns (some #(#{"MATRICOLA" "LAST_NAME" "FIRST_NAME"} (:kind %)) visible)
                            :on-added (fn [row]
                                        (swap! data update :studentRows conj row)
                                        (swap! row-order conj (:id row)))}]
              [:p.hint "Frecce per spostarsi · Invio o digitazione per modificare · Invio/Tab per confermare · Esc per annullare · Canc per svuotare"]])
           (let [{:keys [type column]} @open-dialog]
             (case type
               :add-column [column-dialog {:table-id table-id :on-saved column-saved :on-close close-dialog}]
               :edit-column ^{:key (:id column)} [column-dialog {:table-id table-id :column column :on-saved column-saved :on-close close-dialog}]
               :columns (when view [columns-dialog {:columns tableColumns :view view :on-change update-view! :on-close close-dialog}])
               :new-view [name-dialog {:title "Nuova vista" :initial (str (:name view) " (copia)") :action "Crea vista"
                                       :on-submit create-view :on-close close-dialog}]
               :rename-view [name-dialog {:title "Rinomina vista" :initial (:name view) :action "Rinomina"
                                          :on-submit rename-view :on-close close-dialog}]
               nil))])))))
