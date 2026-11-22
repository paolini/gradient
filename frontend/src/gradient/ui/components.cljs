(ns gradient.ui.components
  "Componenti condivisi: finestra di dialogo, barra superiore, messaggi."
  (:require [clojure.string :as str]
            [reagent.core :as r]))

(defn dialog
  "Finestra modale nativa: Esc e il pulsante di chiusura chiamano `on-close`."
  [{:keys [title wide on-close]} & children]
  (r/with-let [node (atom nil)]
    (into [:dialog {:ref (fn [el]
                           (when (and el (not (.-open el))) (.showModal el))
                           (when (and (nil? el) @node) (.close @node))
                           (reset! node el))
                    :class (if wide "dialog wide" "dialog")
                    :aria-label title
                    :on-cancel (fn [e] (.preventDefault e) (on-close))}
           [:div.dialog-heading
            [:h2 title]
            [:button.icon-button {:type "button" :aria-label "Chiudi" :on-click on-close} "✕"]]]
          children)))

(defn load-error [text on-retry]
  [:div.error {:role "alert"} text " " [:button {:type "button" :on-click on-retry} "Riprova"]])

(defn not-found [text]
  [:div.empty-state [:p text] [:a {:href "#/"} "Torna ai corsi"]])

(defn- initials [name]
  (let [parts (remove str/blank? (str/split (str/trim name) #"\s+"))]
    (str/upper-case (if (> (count parts) 1)
                      (str (first (first parts)) (first (last parts)))
                      (subs (or (first parts) "?") 0 (min 2 (count (or (first parts) "?"))))))))

(defn top-bar
  "Barra con il nome dell'applicazione e il menu utente; si chiude con Esc o clic esterno."
  [_]
  (let [open (r/atom false)
        container (atom nil)
        button (atom nil)
        on-pointer (fn [e] (when-not (some-> @container (.contains (.-target e))) (reset! open false)))
        on-key (fn [e] (when (= "Escape" (.-key e)) (reset! open false) (some-> @button .focus)))]
    (r/create-class
     {:component-did-mount (fn [_]
                             (js/document.addEventListener "pointerdown" on-pointer)
                             (js/document.addEventListener "keydown" on-key))
      :component-will-unmount (fn [_]
                                (js/document.removeEventListener "pointerdown" on-pointer)
                                (js/document.removeEventListener "keydown" on-key))
      :reagent-render
      (fn [{:keys [user pending on-logout]}]
        [:header.topbar
         [:div.topbar-inner
          [:span.topbar-brand "Gradient"]
          [:div.user-menu {:ref #(reset! container %)}
           [:button.user-button {:ref #(reset! button %) :type "button" :aria-haspopup "menu"
                                 :aria-expanded @open :on-click #(swap! open not)}
            [:span.avatar {:aria-hidden true} (initials (:name user))]
            [:span.user-name (:name user)]
            [:span.caret {:aria-hidden true} "▾"]]
           (when @open
             [:div.user-dropdown {:role "menu" :aria-label "Account"}
              [:div.user-info
               [:strong (:name user)] [:span (:email user)]
               [:span "Accesso con " (if (= "google" (:provider user)) "Google" "UniPi")]]
              [:button {:ref #(some-> % .focus) :type "button" :role "menuitem" :disabled pending
                        :on-click (fn [] (reset! open false) (on-logout))}
               "Esci"]])]]])})))
