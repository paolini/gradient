(ns gradient.config
  "Configurazione da variabili d'ambiente, con `.env` come riserva per lo sviluppo."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net URI)))

(defn- parse-env-file [file]
  (if (.isFile (io/file file))
    (into {}
          (keep (fn [line]
                  (let [line (str/trim line)]
                    (when-let [[_ k v] (and (not (str/starts-with? line "#"))
                                            (re-matches #"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)" line))]
                      [k (str/replace v #"^(['\"])(.*)\1$" "$2")]))))
          (str/split-lines (slurp file)))
    {}))

(def defaults
  {"AUTH_BASE_URL" "http://127.0.0.1:5173"
   "DATOMIC_STORAGE_DIR" "data"
   "DATOMIC_DB_NAME" "gradient"
   "HTTP_HOST" "127.0.0.1"
   "HTTP_PORT" "8000"
   "GRADIENT_STATIC_DIR" "frontend/public"
   "WSO2_AUTHORIZE_URL" "https://iam.unipi.it/oauth2/authorize"
   "WSO2_TOKEN_URL" "https://iam.unipi.it/oauth2/token"
   "WSO2_USERINFO_URL" "https://iam.unipi.it/oauth2/userinfo"})

(defn- fail [message]
  (throw (ex-info message {::invalid true})))

(defn validate
  "Stesse regole della versione Python: origine pubblica esplicita, HTTPS fuori da localhost."
  [{:keys [auth-base-url auth-secret google-client-id wso2-client-id] :as config}]
  (let [uri (try (URI. auth-base-url) (catch Exception _ nil))
        scheme (some-> uri .getScheme)]
    (when (or (nil? uri) (not (#{"http" "https"} scheme)) (str/blank? (.getHost uri))
              (not (#{nil "" "/"} (.getRawPath uri))) (.getRawQuery uri) (.getRawFragment uri)
              (.getRawUserInfo uri))
      (fail "AUTH_BASE_URL deve essere un'origine HTTP(S) senza percorso."))
    (when (and (= "http" scheme) (not (#{"localhost" "127.0.0.1" "[::1]"} (.getHost uri))))
      (fail "Usare HTTPS fuori dallo sviluppo locale."))
    (when (and auth-secret (< (count auth-secret) 32))
      (fail "AUTH_SECRET deve contenere almeno 32 caratteri casuali."))
    (when (and (or google-client-id wso2-client-id) (not auth-secret))
      (fail "Configurare AUTH_SECRET prima dei provider OAuth."))
    (doseq [endpoint ((juxt :wso2-authorize-url :wso2-token-url :wso2-userinfo-url) config)]
      (when-not (str/starts-with? endpoint "https://")
        (fail "Gli endpoint UniPi devono usare HTTPS.")))
    config))

(defn load-config
  "Le variabili d'ambiente hanno precedenza su `.env`."
  ([] (load-config (System/getenv) ".env"))
  ([environment env-file]
   (let [values (merge defaults (parse-env-file env-file) (into {} environment))
         value (fn [k] (not-empty (str/trim (get values k ""))))]
     (validate
      {:auth-base-url (str/replace (value "AUTH_BASE_URL") #"/$" "")
       :auth-secret (value "AUTH_SECRET")
       :google-client-id (value "GOOGLE_CLIENT_ID")
       :google-client-secret (value "GOOGLE_CLIENT_SECRET")
       :wso2-client-id (value "WSO2_CLIENT_ID")
       :wso2-client-secret (value "WSO2_CLIENT_SECRET")
       :wso2-authorize-url (value "WSO2_AUTHORIZE_URL")
       :wso2-token-url (value "WSO2_TOKEN_URL")
       :wso2-userinfo-url (value "WSO2_USERINFO_URL")
       :storage-dir (.getAbsolutePath (io/file (value "DATOMIC_STORAGE_DIR")))
       :db-name (value "DATOMIC_DB_NAME")
       :host (value "HTTP_HOST")
       :port (parse-long (value "HTTP_PORT"))
       :static-dir (value "GRADIENT_STATIC_DIR")}))))
