(ns gradient.views
  "Viste personali con nome: ordine e visibilità delle colonne, ordinamento delle righe.
  Appartengono all'utente e non modificano la tabella."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [gradient.cells :as cells]
            [gradient.columns :as columns]
            [gradient.db :as db]
            [gradient.store :as store :refer [identifier input-error]])
  (:import (java.util Date)))

(def default-name "Predefinita")
(def ^:private default-sort [[:last-name false] [:first-name false]])

(def view-pattern
  [:db/id :view/id {:view/user [:user/id]} {:view/table [:table/id]} :view/name
   :view/column-order :view/hidden-columns :view/row-sort :view/last-used])

(defn- read-edn [s] (if s (edn/read-string s) []))

(defn- effective-view
  "Unisce la vista salvata (nil per quella predefinita) con le colonne attuali:
  le colonne aggiunte dopo vanno in fondo, quelle eliminate spariscono."
  [columns saved active]
  (let [ids (map (comp str :column/id) columns)
        known (set ids)
        order (filterv known (read-edn (:view/column-order saved)))
        order (into order (remove (set order)) ids)
        sort (if saved
               (filterv (comp known :column-id) (read-edn (:view/row-sort saved)))
               (let [by-kind (into {} (map (juxt :column/kind (comp str :column/id))) columns)]
                 (vec (for [[kind descending] default-sort
                            :when (by-kind kind)]
                        {:column-id (by-kind kind) :descending descending}))))]
    {:id (some-> saved :view/id str)
     :name (:view/name saved default-name)
     :active active
     :column-order order
     :hidden-columns (filterv known (read-edn (:view/hidden-columns saved)))
     :row-sort sort}))

(defn- saved-views [db user-id table-id]
  (->> (d/q '[:find (pull ?v pattern) :in $ ?u ?t pattern
              :where [?user :user/id ?u] [?table :table/id ?t] [?v :view/user ?user] [?v :view/table ?table]]
            db user-id table-id view-pattern)
       (map first)
       (sort (fn [a b] (store/by-keys [:view/name (comp str :view/id)] a b)))))

(defn- effective-views
  "Senza viste salvate ne restituisce una predefinita, non memorizzata; è attiva l'ultima usata."
  [columns saved]
  (if (empty? saved)
    [(effective-view columns nil true)]
    (let [latest (last (sort-by (juxt :view/last-used (comp str :view/id)) saved))]
      (mapv #(effective-view columns % (= % latest)) saved))))

(defn views [{:keys [conn user-id] :as store} table-id]
  (let [table-id (identifier table-id "tableId")
        db (d/db conn)]
    (store/require-owned store db [:table table-id] "tableId")
    (effective-views (columns/table-columns db table-id) (saved-views db user-id table-id))))

(defn- next-use
  "Istante successivo a quello di tutte le altre viste della tabella: l'ultima vista usata
  resta riconoscibile anche se due richieste cadono nello stesso millisecondo."
  [db user-id table-id]
  (let [latest (ffirst (d/q '[:find (max ?when) :in $ ?u ?t
                              :where [?user :user/id ?u] [?table :table/id ?t]
                              [?v :view/user ?user] [?v :view/table ?table] [?v :view/last-used ?when]]
                            db user-id table-id))
        now (System/currentTimeMillis)]
    (Date. (long (if latest (max now (inc (.getTime ^Date latest))) now)))))

(defn- require-view
  "La vista deve appartenere all'utente (e alla tabella, se indicata) e la tabella a un suo corso."
  [{:keys [user-id] :as store} db view-id table-id]
  (let [view-id (identifier view-id "viewId")
        view (ffirst (d/q '[:find (pull ?v pattern) :in $ ?id pattern :where [?v :view/id ?id]]
                          db view-id view-pattern))]
    (when (or (nil? view)
              (not= user-id (-> view :view/user :user/id))
              (and table-id (not= table-id (-> view :view/table :table/id))))
      (throw (input-error "viewId" "Vista non trovata." "NOT_FOUND")))
    (store/require-owned store db [:table (-> view :view/table :table/id)] "viewId")
    view))

(defn save-view
  "Crea (senza `view-id`) o aggiorna una vista e la rende quella in uso."
  [{:keys [conn user-id] :as store} {:keys [table-id view-id name column-order hidden-columns row-sort]}]
  (let [table-id (identifier table-id "tableId")
        _ (when-not user-id
            (throw (input-error "tableId" "Le viste personali richiedono un utente autenticato.")))
        name (store/text name "name" :maximum 100)
        column-order (mapv #(str (identifier % "columnOrder")) column-order)
        hidden (mapv #(str (identifier % "hiddenColumns")) hidden-columns)
        row-sort (mapv (fn [{:keys [column-id descending]}]
                         {:column-id (str (identifier column-id "rowSort")) :descending (boolean descending)})
                       row-sort)
        db (d/db conn)]
    (store/require-owned store db [:table table-id] "tableId")
    (let [columns (columns/table-columns db table-id)
          known (set (map (comp str :column/id) columns))]
      (when-not (every? known (concat column-order hidden (map :column-id row-sort)))
        (throw (input-error "columnOrder" "La vista contiene colonne che non appartengono alla tabella." "NOT_FOUND")))
      (when (not= (count (distinct column-order)) (count column-order))
        (throw (input-error "columnOrder" "Colonne ripetute nell'ordine.")))
      (let [view (when view-id (require-view store db view-id table-id))
            id (or (:view/id view) (random-uuid))
            values {:view/name name
                    :view/name-key (cells/name-key name)
                    :view/column-order (pr-str column-order)
                    :view/hidden-columns (pr-str (vec (sort (set hidden))))
                    :view/row-sort (pr-str row-sort)
                    :view/last-used (next-use db user-id table-id)}
            tx (if view
                 (db/set-attrs-tx (:db/id view) view values)
                 [(merge {:view/id id :view/user [:user/id user-id] :view/table [:table/id table-id]} values)])
            result (store/transact-unique store tx (input-error "name" "Esiste già una vista con questo nome." "DUPLICATE_NAME"))]
        (effective-view columns (store/pull-after result view-pattern [:view/id id]) true)))))

(defn select-view
  "Rende la vista quella in uso per la sua tabella."
  [{:keys [conn user-id] :as store} {:keys [view-id]}]
  (let [db (d/db conn)
        view (require-view store db view-id nil)
        table-id (-> view :view/table :table/id)
        result (store/transact store [[:db/add (:db/id view) :view/last-used (next-use db user-id table-id)]])]
    (effective-view (columns/table-columns (:db-after result) table-id)
                    (store/pull-after result view-pattern [:view/id (:view/id view)])
                    true)))

(defn delete-view
  "Elimina la vista e restituisce quelle rimaste."
  [{:keys [conn user-id] :as store} {:keys [view-id]}]
  (let [view (require-view store (d/db conn) view-id nil)
        table-id (-> view :view/table :table/id)
        db-after (:db-after (store/transact store [[:db/retractEntity (:db/id view)]]))]
    (effective-views (columns/table-columns db-after table-id) (saved-views db-after user-id table-id))))
