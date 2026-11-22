(ns gradient.main
  "Server HTTP: autenticazione, GraphQL, controllo di salute e frontend statico."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [gradient.auth :as auth]
            [gradient.config :as config]
            [gradient.db :as db]
            [gradient.graphql :as graphql]
            [jsonista.core :as json]
            [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]))

(def max-body-bytes (* 4 1024 1024))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
   :body (json/write-value-as-string body)})

(defn- graphql-handler [{:keys [config conn schema]} request]
  (let [user (auth/current-user conn (auth/request-token request))]
    (cond
      (nil? user) (auth/denied 401 "Accedi per utilizzare Gradient.")
      :else
      (or (auth/check-origin config request)
          (let [body (try (json/read-value (:body request) json/keyword-keys-object-mapper)
                          (catch Exception _ nil))]
            (if-not (and (map? body) (string? (:query body)))
              (json-response 400 {:errors [{:message "Richiesta GraphQL non valida."}]})
              (json-response 200 (graphql/execute schema
                                                  body
                                                  {:user user
                                                   :store {:conn conn :user-id (parse-uuid (:id user))}}))))))))

(defn- wrap-database-errors
  "Errori di database fuori da GraphQL: diagnosi nei log, messaggio generico al client."
  [handler]
  (fn [request]
    (try (handler request)
         (catch Exception error
           (if (db/database-error? error)
             (do (db/log-error error)
                 (json-response 503 {:detail "Database non disponibile. Verificare la configurazione."}))
             (throw error))))))

(defn- wrap-body-limit [handler]
  (fn [request]
    (if (> (or (:content-length request) 0) max-body-bytes)
      (json-response 413 {:detail "Richiesta troppo grande."})
      (handler request))))

(defn app [{:keys [config] :as system}]
  (let [static-dir (:static-dir config)]
    (-> (ring/ring-handler
         (ring/router
          [["/healthz" {:get (fn [_] (json-response 200 {:status "ok"}))}]
           ["/auth"
            ["/session" {:get (partial auth/session-info system)}]
            ["/login/:provider" {:get (partial auth/login system)}]
            ["/callback/:provider" {:get (partial auth/callback system)}]
            ["/logout" {:post (partial auth/logout system)}]]
           ["/graphql" {:post (partial graphql-handler system)}]])
         (ring/routes
          (if (and static-dir (.isDirectory (io/file static-dir)))
            (ring/create-file-handler {:path "/" :root static-dir})
            (constantly nil))
          (ring/create-default-handler)))
        wrap-database-errors
        wrap-params
        wrap-cookies
        wrap-body-limit)))

(defn system [config]
  {:config config
   :conn (db/connect config)
   :schema (graphql/load-schema)
   ;; Senza AUTH_SECRET nessun provider è attivo: il segreto casuale basta per lo stato OAuth.
   :secret (or (:auth-secret config) (auth/token 32))})

(defn -main [& args]
  (let [config (config/load-config)]
    (case (first args)
      "migrate" (do (db/connect config)
                    (log/info "Schema Datomic installato in" (:storage-dir config))
                    (shutdown-agents))
      (let [{:keys [host port]} config
            ;; Pochi thread: l'utilizzo previsto è ridotto e la RAM va contenuta.
            stop (http/run-server (app (system config)) {:ip host :port port :thread 4
                                                         :max-body max-body-bytes})]
        (log/info (str "Gradient in ascolto su http://" host ":" port))
        (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop :timeout 5000)))))))
