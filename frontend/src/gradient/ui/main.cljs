(ns gradient.ui.main
  "Punto d'ingresso: verifica della sessione, pagina di accesso e applicazione."
  (:require [gradient.ui.app :refer [app]]
            [gradient.ui.components :refer [top-bar]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

(defonce session (r/atom {:loaded false :user nil :providers [] :error "" :pending false}))

(defn load-session! []
  (swap! session assoc :loaded false)
  (-> (js/fetch "/auth/session")
      (.then (fn [response]
               (when-not (.-ok response) (throw (js/Error.)))
               (.json response)))
      (.then (fn [data]
               (let [{:keys [user providers]} (js->clj data :keywordize-keys true)]
                 (swap! session assoc :loaded true :user user :providers providers))))
      (.catch #(swap! session assoc :error "Impossibile verificare la sessione. Controlla il backend e ricarica la pagina."))))

(defn logout! []
  (swap! session assoc :pending true :error "")
  (-> (js/fetch "/auth/logout" #js {:method "POST" :headers #js {"X-Gradient-Request" "1"}})
      (.then #(if (.-ok %) (swap! session assoc :user nil) (throw (js/Error.))))
      (.catch #(swap! session assoc :error "Uscita non riuscita. Riprova."))
      (.finally #(swap! session assoc :pending false))))

(defn root []
  (let [{:keys [loaded user providers error pending]} @session]
    (cond
      (not loaded)
      [:main [:h1 "Gradient"] [:p {:role "status"} (if (seq error) error "Verifica della sessione…")]]
      (nil? user)
      [:main
       [:h1 "Gradient"]
       [:p "Accedi per gestire i tuoi corsi e le valutazioni."]
       (when (.has (js/URLSearchParams. (.-search js/location)) "auth_error")
         [:p {:role "alert"} "Accesso non riuscito o annullato. Riprova."])
       (for [provider providers]
         ^{:key provider}
         [:p [:a {:href (str "/auth/login/" provider)} "Accedi con " (if (= "google" provider) "Google" "UniPi")]])
       (when (empty? providers)
         [:p "Nessun metodo di accesso configurato. Contatta chi gestisce il servizio."])]
      :else
      [:<>
       [top-bar {:user user :pending pending :on-logout logout!}]
       (when (seq error) [:p.error.topbar-error {:role "alert"} error])
       ^{:key (:id user)} [app]])))

(defonce react-root (delay (rdc/create-root (js/document.getElementById "root"))))

(defn ^:dev/after-load render []
  (rdc/render @react-root [root]))

(defn init []
  (js/window.addEventListener "gradient-session-expired" load-session!)
  (load-session!)
  (render))
