(ns gradient.ui.api
  "Chiamate GraphQL; le operazioni sono in `gradient.ui.operations`."
  (:require [gradient.ui.operations :refer [operations]]))

(defn- fail [message] (throw (js/Error. message)))

(defn graphql
  "Promessa con i dati della risposta come mappa Clojure con chiavi keyword."
  ([operation] (graphql operation {}))
  ([operation variables]
   (-> (js/fetch "/graphql"
                 #js {:method "POST"
                      :headers #js {"Content-Type" "application/json" "X-Gradient-Request" "1"}
                      :body (js/JSON.stringify (clj->js {:query (operations operation) :variables variables}))})
       (.then identity (fn [_] (fail "Backend non raggiungibile. Verifica che sia avviato.")))
       (.then (fn [response]
                (when (= 401 (.-status response))
                  (js/window.dispatchEvent (js/Event. "gradient-session-expired"))
                  (fail "Sessione scaduta. Accedi nuovamente."))
                (when-not (.-ok response)
                  (fail (str "Il backend ha risposto con errore HTTP " (.-status response) ".")))
                (.json response)))
       (.then (fn [body]
                (let [{:keys [data errors]} (js->clj body :keywordize-keys true)]
                  (when (seq errors) (fail (apply str (interpose " " (map :message errors)))))
                  (when-not data (fail "Risposta del backend non valida."))
                  data))))))

(defn require-success
  "Gli errori di validazione delle mutation diventano un'eccezione con i messaggi."
  [result]
  (when (seq (:errors result))
    (fail (apply str (interpose " " (map :message (:errors result))))))
  result)

(defn message [error fallback]
  (if (instance? js/Error error) (.-message error) fallback))
