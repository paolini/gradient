(ns gradient.ui.column-dialog
  "Finestra per creare una colonna o modificarne nome e scala."
  (:require [clojure.string :as str]
            [gradient.ui.api :as api]
            [gradient.ui.columns :refer [builtin?]]
            [gradient.ui.components :refer [dialog]]
            [reagent.core :as r]))

(def ^:private modifier-options [["+" "+ (più)"] ["-" "− (meno)"] ["L" "L (lode)"]])

(defn column-dialog
  "Senza `:column` crea una nuova colonna nella tabella `:table-id`."
  [{:keys [column]}]
  (let [scale (or (:grade column) {:modifiers []})
        state (r/atom {:name (or (:name column) "")
                       :kind (or (:kind column) "GRADE")
                       :maximum (or (:maximum scale) "")
                       :threshold (or (:passThreshold scale) "")
                       :above (boolean (:allowAboveMaximum scale))
                       :negative (boolean (:allowNegative scale))
                       :modifiers (vec (:modifiers scale))
                       :pending false
                       :error ""})]
    (fn [{:keys [table-id column on-saved on-close]}]
      (let [{:keys [name kind maximum threshold above negative modifiers pending error]} @state
            field (fn [k] {:value (k @state) :on-change #(swap! state assoc k (.. % -target -value))})
            check (fn [k] {:type "checkbox" :checked (k @state) :on-change #(swap! state assoc k (.. % -target -checked))})
            toggle (fn [modifier]
                     (swap! state update :modifiers
                            #(if (some #{modifier} %) (filterv (partial not= modifier) %) (conj % modifier))))
            submit (fn [e]
                     (.preventDefault e)
                     (swap! state assoc :pending true :error "")
                     (let [grade (when (= kind "GRADE")
                                   {:maximum (not-empty (str/trim maximum))
                                    :passThreshold (not-empty (str/trim threshold))
                                    :allowAboveMaximum above
                                    :allowNegative negative
                                    :modifiers modifiers})
                           request (if column
                                     (-> (api/graphql :update-column {:input (cond-> {:columnId (:id column) :name name}
                                                                              grade (assoc :grade grade))})
                                         (.then #(:column (api/require-success (:updateColumn %)))))
                                     (-> (api/graphql :add-column {:input {:tableId table-id :kind kind :name name :grade grade}})
                                         (.then #(:column (api/require-success (:addColumn %))))))]
                       (-> request
                           (.then (fn [saved]
                                    (when-not saved (throw (js/Error. "Colonna non restituita dal server.")))
                                    (on-saved saved)))
                           (.catch #(swap! state assoc :error (api/message % "Salvataggio non riuscito.")))
                           (.finally #(swap! state assoc :pending false)))))]
        [dialog {:title (if column (str "Colonna «" (:name column) "»") "Nuova colonna") :on-close on-close}
         [:form {:on-submit submit}
          (when (seq error) [:p.error {:role "alert"} error])
          [:fieldset {:disabled pending}
           [:label "Nome"
            [:input (merge (field :name) {:required true :max-length 100 :auto-focus true
                                          :placeholder (if (= kind "GRADE") "Es1" "Presente")})]]
           (when-not column
             [:label "Tipo"
              [:select (field :kind)
               [:option {:value "GRADE"} "Voto (anche punteggio di un esercizio)"]
               [:option {:value "FLAG"} "Flag: sì, no o vuoto (presente, consegna…)"]
               [:option {:value "TEXT"} "Testo"]]])
           (when (and (= kind "GRADE") (not (and column (builtin? column))))
             [:<>
              [:div.field-row
               [:label "Massimo (facoltativo)" [:input (merge (field :maximum) {:input-mode "decimal" :placeholder "30"})]]
               [:label "Sufficienza (facoltativa)" [:input (merge (field :threshold) {:input-mode "decimal" :placeholder "18"})]]]
              [:div.checks
               [:label.check [:input (check :above)] " Ammetti valori sopra il massimo"]
               [:label.check [:input (check :negative)] " Ammetti valori negativi"]]
              [:fieldset.checks
               [:legend "Modificatori ammessi"]
               (for [[value label] modifier-options]
                 ^{:key value}
                 [:label.check [:input {:type "checkbox" :checked (boolean (some #{value} modifiers))
                                        :on-change #(toggle value)}]
                  " " label])]])
           [:div.dialog-actions
            [:button.secondary {:type "button" :on-click on-close} "Annulla"]
            [:button (if column "Salva" "Aggiungi colonna")]]]]]))))
