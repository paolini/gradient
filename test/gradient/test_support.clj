(ns gradient.test-support
  (:require [gradient.auth :as auth]
            [gradient.config :as config]
            [gradient.main :as main]
            [jsonista.core :as json])
  (:import (java.io ByteArrayInputStream)
           (java.util Base64)))

(defn test-config [& {:as overrides}]
  (merge (config/load-config {"AUTH_SECRET" (apply str (repeat 40 "s"))} "/nonexistent/.env")
         {:storage-dir :mem :db-name (str "test-" (random-uuid))}
         overrides))

(defn test-system [& overrides]
  (main/system (apply test-config overrides)))

(defn login!
  "Sessione reale per un utente fittizio; restituisce il token del cookie."
  ([system] (login! system "sub-1"))
  ([system subject]
   (auth/save-login (:conn system) "google"
                    {"sub" subject "email" (str subject "@example.invalid") "email_verified" true "name" "Docente Prova"}
                    nil)))

(defn request [system method uri & {:keys [token body headers query-string]}]
  ((main/app system)
   (cond-> {:request-method method :uri uri :scheme :http :server-name "127.0.0.1" :server-port 8000
            :headers (into {} (remove (comp nil? val))
                           (merge {"x-gradient-request" "1"}
                                  (when token {"cookie" (str "gradient_session=" token)})
                                  headers))}
     query-string (assoc :query-string query-string)
     body (assoc :body (ByteArrayInputStream. (json/write-value-as-bytes body))))))

(defn body [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

(defn gql
  "Esegue un'operazione GraphQL autenticata e restituisce il corpo JSON."
  [system token query & [variables]]
  (body (request system :post "/graphql" :token token :body {:query query :variables (or variables {})})))

(defn encoded
  ([s] (encoded s "UTF-8"))
  ([^String s charset] (.encodeToString (Base64/getEncoder) (.getBytes s ^String charset))))
