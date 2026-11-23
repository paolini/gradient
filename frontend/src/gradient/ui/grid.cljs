(ns gradient.ui.grid
  "Griglia stile foglio di calcolo: frecce per spostarsi, Invio o digitazione per modificare,
  Invio/Tab per confermare, Esc per annullare, Canc per svuotare."
  (:require [clojure.string :as str]
            [gradient.ui.columns :refer [below-threshold? edit-text find-cell]]
            [reagent.core :as r]))

(defn- cell-key [row column] (str (:id row) ":" (:id column)))

(defn- kind-class [column] (str "kind-" (str/lower-case (:kind column))))

(defn- display [row column]
  (case (:kind column)
    "MATRICOLA" (or (:matricola row) [:span.muted "Da inserire"])
    "LAST_NAME" (:lastName row)
    "FIRST_NAME" (:firstName row)
    "EMAIL" (:email row)
    "DSA" (when (:dsa row) "Sì")
    "DIS" (when (:dis row) "Sì")
    (:display (find-cell row column))))

(def ^:private moves {"ArrowUp" [-1 0] "ArrowDown" [1 0] "ArrowLeft" [0 -1] "ArrowRight" [0 1]})

(defn- printable? [e]
  (and (= 1 (count (.-key e))) (not (.-ctrlKey e)) (not (.-metaKey e)) (not (.-altKey e))))

(defn grid
  "Props: `:columns` e `:rows` (vettori), `:header` e `:footer` (funzioni colonna → hiccup,
  la seconda facoltativa), `:on-commit` (funzione riga colonna testo → promessa del
  messaggio d'errore, oppure nil se il salvataggio è riuscito)."
  [_]
  (let [active (r/atom {:row 0 :column 0})
        editing (r/atom nil)                ; {:draft "..."} mentre l'editor è aperto
        states (r/atom {})                  ; cella → {:status :saving/:error, :draft, :message}
        props (atom nil)                    ; ultime props, lette dai gestori di eventi
        editing? (atom false)               ; Invio e perdita del focus confermano una sola volta
        focus-pending (atom false)          ; spostare il focus sulla cella attiva dopo il rendering
        table-node (atom nil)
        input-node (atom nil)
        current (fn []
                  (let [{:keys [rows columns]} @props
                        {:keys [row column]} @active]
                    {:row (min row (max 0 (dec (count rows))))
                     :column (min column (max 0 (dec (count columns))))}))
        at (fn [{:keys [row column]}]
             (let [{:keys [rows columns]} @props]
               [(get rows row) (get columns column)]))
        focus-cell (fn [{:keys [row column]}]
                     (some-> @table-node (.querySelector (str "[data-cell=\"" row ":" column "\"]")) .focus))
        move (fn [row-delta column-delta]
               (let [{:keys [rows columns]} @props
                     {:keys [row column]} (current)]
                 (reset! focus-pending true)
                 (reset! active {:row (max 0 (min (dec (count rows)) (+ row row-delta)))
                                 :column (max 0 (min (dec (count columns)) (+ column column-delta)))})))
        start-edit (fn [initial]
                     (let [[row column] (at (current))]
                       (when (and row column)
                         (let [state (get @states (cell-key row column))]
                           (reset! editing? true)
                           (reset! editing {:draft (or initial
                                                       (if (= :error (:status state)) (:draft state) (edit-text row column)))})))))
        commit (fn [position value]
                 (let [[row column] (at position)]
                   (when (and row column)
                     (let [k (cell-key row column)]
                       (when-not (and (= value (edit-text row column)) (not= :error (:status (get @states k))))
                         (swap! states assoc k {:status :saving :draft value})
                         (-> ((:on-commit @props) row column value)
                             (.then (fn [error]
                                      (if error
                                        (swap! states assoc k {:status :error :draft value :message error})
                                        (swap! states dissoc k))))))))))
        finish-edit (fn [row-delta column-delta focus?]
                      (when (and @editing @editing?)
                        (reset! editing? false)
                        (let [position (current)
                              value (:draft @editing)]
                          (reset! editing nil)
                          (commit position value)
                          (when focus? (move row-delta column-delta)))))
        cancel-edit (fn []
                      (reset! editing? false)
                      (reset! focus-pending true)
                      (reset! editing nil))
        editor-key (fn [e]
                     (let [k (.-key e)
                           shift (.-shiftKey e)]
                       (case k
                         "Enter" (do (.preventDefault e) (finish-edit (if shift -1 1) 0 true))
                         "Tab" (do (.preventDefault e) (finish-edit 0 (if shift -1 1) true))
                         ("ArrowUp" "ArrowDown") (do (.preventDefault e) (finish-edit (if (= k "ArrowUp") -1 1) 0 true))
                         "Escape" (do (.preventDefault e) (cancel-edit))
                         nil)))
        grid-key (fn [e]
                   (let [{:keys [rows columns]} @props
                         k (.-key e)
                         {:keys [column]} (current)
                         last-column (dec (count columns))]
                     (cond
                       ;; L'editor è aperto ma non ancora nel DOM (Reagent disegna al fotogramma
                       ;; successivo): i tasti digitati velocemente non devono andare persi.
                       @editing? (cond
                                   (printable? e) (do (.preventDefault e) (swap! editing update :draft str k))
                                   (= k "Backspace") (do (.preventDefault e) (swap! editing update :draft #(subs % 0 (max 0 (dec (count %))))))
                                   :else (editor-key e))
                       (or (empty? rows) (empty? columns)) nil
                       (.closest (.-target e) "thead, tfoot") nil
                       (moves k) (do (.preventDefault e) (apply move (moves k)))
                       (= k "Tab") (when-not (if (.-shiftKey e) (zero? column) (= column last-column))
                                     (.preventDefault e)
                                     (move 0 (if (.-shiftKey e) -1 1)))
                       (= k "Home") (do (.preventDefault e) (move 0 (- column)))
                       (= k "End") (do (.preventDefault e) (move 0 (- last-column column)))
                       (#{"Enter" "F2"} k) (do (.preventDefault e) (start-edit nil))
                       (#{"Delete" "Backspace"} k) (do (.preventDefault e) (commit (current) ""))
                       (printable? e) (do (.preventDefault e) (start-edit k)))))
        ;; Funzione stabile: React la chiama soltanto quando l'editor compare o scompare,
        ;; quindi il cursore viene messo in fondo una volta sola.
        input-ref (fn [el]
                    (when (and el (not= el @input-node))
                      (.focus el)
                      (let [n (count (.-value el))] (.setSelectionRange el n n)))
                    (reset! input-node el))
        after-render (fn [_]
                       (when (and @focus-pending (not @editing))
                         (reset! focus-pending false)
                         (focus-cell (current))))]
    (r/create-class
     {:display-name "grid"
      :component-did-mount after-render
      :component-did-update after-render
      :reagent-render
      (fn [{:keys [columns rows header footer] :as p}]
        (reset! props p)
        (let [{active-row :row active-column :column} (current)
              edit @editing
              cell-states @states]
          [:div.table-scroll.sheet {:on-key-down grid-key}
           [:table.grid {:ref #(reset! table-node %)}
            [:thead
             [:tr (for [column columns]
                    ^{:key (:id column)} [:th {:scope "col" :class (kind-class column)} (header column)])]]
            [:tbody
             (for [[row-index row] (map-indexed vector rows)]
               ^{:key (:id row)}
               [:tr
                (for [[column-index column] (map-indexed vector columns)
                      :let [active? (and (= row-index active-row) (= column-index active-column))
                            state (get cell-states (cell-key row column))]]
                  ^{:key (:id column)}
                  [:td {:data-cell (str row-index ":" column-index)
                        :tab-index (if active? 0 -1)
                        :class [(kind-class column)
                                (when active? "active")
                                (some-> (:status state) name)
                                (when (and (= "GRADE" (:kind column)) (below-threshold? (find-cell row column) column)) "below")]
                        :title (when (= :error (:status state)) (:message state))
                        :on-click #(when-not active? (reset! active {:row row-index :column column-index}))
                        :on-double-click #(do (reset! active {:row row-index :column column-index}) (start-edit nil))}
                   (cond
                     (and active? edit)
                     [:input.cell-editor {:ref input-ref
                                          :value (:draft edit)
                                          :aria-label (str (:name column) ", " (:lastName row) " " (:firstName row))
                                          :on-change #(swap! editing assoc :draft (.. % -target -value))
                                          :on-key-down (fn [e] (.stopPropagation e) (editor-key e))
                                          :on-blur #(finish-edit 0 0 false)}]
                     state (:draft state)
                     :else (display row column))])])]
            (when footer
              [:tfoot [:tr.new-row (for [column columns] ^{:key (:id column)} [:td (footer column)])]])]]))})))
