(ns user
  "Sviluppo interattivo: `(start)`, `(stop)`, `(restart)` dalla REPL (`make repl`)."
  (:require [gradient.config :as config]
            [gradient.main :as main]
            [org.httpkit.server :as http]))

(defonce state (atom nil))

(defn start []
  (let [system (main/system (config/load-config))
        {:keys [host port]} (:config system)]
    ;; `main/app` è richiamato a ogni richiesta: le modifiche valutate nella REPL sono subito attive.
    (reset! state {:system system
                   :stop (http/run-server (fn [request] ((main/app system) request)) {:ip host :port port})})
    (println (str "Backend su http://" host ":" port))))

(defn stop []
  (when-let [{:keys [stop]} @state]
    (stop)
    (reset! state nil)))

(defn restart [] (stop) (start))

(comment
  (start)
  ;; Interrogare direttamente il database
  (require '[datomic.client.api :as d])
  (d/q '[:find ?name ?year :where [?c :course/name ?name] [?c :course/academic-year ?year]]
       (d/db (get-in @state [:system :conn])))
  (stop))
