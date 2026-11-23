(ns gradient.ui.common
  "Collegamenti tra le pagine, caricamento degli elenchi e formattazione condivisi."
  (:require [clojure.string :as str]
            [gradient.ui.api :as api]))

(defn course-href [course-id] (str "#/corsi/" (js/encodeURIComponent course-id)))
(defn table-href [course-id table-id] (str (course-href course-id) "/tabelle/" (js/encodeURIComponent table-id)))
(defn go! [href] (set! (.-hash js/location) href))

(defn load-list
  "Carica un elenco nella ratom `state`; le risposte superate da un nuovo caricamento vengono ignorate."
  [state operation field variables]
  (let [token (js/Symbol)]
    (reset! state {:items [] :loading true :error "" :token token})
    (-> (api/graphql operation variables)
        (.then #(when (= token (:token @state)) (swap! state assoc :items (get % field) :loading false)))
        (.catch #(when (= token (:token @state)) (swap! state assoc :loading false :error (api/message % "Caricamento non riuscito.")))))
    state))

(defn- format-date [value]
  (let [[year month day] (str/split value #"-")] (str day "/" month "/" year)))

(defn- details [& parts] (str/join " · " (remove str/blank? parts)))
(defn course-details [{:keys [degreeProgram academicYear]}] (details degreeProgram academicYear))
(defn table-details [{:keys [date location]}] (details (some-> date format-date) location))
