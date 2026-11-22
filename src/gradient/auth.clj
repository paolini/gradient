(ns gradient.auth
  "Login OAuth con profilo letto dal provider e sessioni revocabili locali."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [gradient.db :as db]
            [jsonista.core :as json])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Duration Instant)
           (java.util Base64 Date HexFormat)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def session-cookie "gradient_session")
(def oauth-cookie "gradient_oauth")
(def lifetime-seconds (* 60 60 24 7))
(def oauth-seconds 600)

;; ---------------------------------------------------------------------------
;; Primitive crittografiche

(def ^:private random (SecureRandom.))

(defn- base64url [^bytes data]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) data))

(defn token
  "Equivalente di `secrets.token_urlsafe(n)`."
  [n]
  (base64url (let [b (byte-array n)] (.nextBytes random b) b)))

(defn- sha256 [^String value]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes value StandardCharsets/UTF_8)))

(defn digest [value] (.formatHex (HexFormat/of) (sha256 value)))

(defn- hmac [^String secret ^String value]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256"))
    (.doFinal mac (.getBytes value StandardCharsets/UTF_8))))

(defn- same? [^String a ^String b]
  (MessageDigest/isEqual (.getBytes (str a) StandardCharsets/UTF_8) (.getBytes (str b) StandardCharsets/UTF_8)))

(defn sign
  "Cookie firmato e con scadenza, per lo stato OAuth (come la sessione di Starlette)."
  [secret data max-age]
  (let [payload (base64url (json/write-value-as-bytes (assoc data :exp (+ (.getEpochSecond (Instant/now)) max-age))))]
    (str payload "." (base64url (hmac secret payload)))))

(defn unsign [secret value]
  (let [[payload signature] (str/split (str value) #"\." 2)]
    (when (and payload signature (same? signature (base64url (hmac secret payload))))
      (let [data (try (json/read-value (.decode (Base64/getUrlDecoder) ^String payload) json/keyword-keys-object-mapper)
                      (catch Exception _ nil))]
        (when (and (map? data) (int? (:exp data)) (> (:exp data) (.getEpochSecond (Instant/now))))
          (dissoc data :exp))))))

;; ---------------------------------------------------------------------------
;; Provider

(defn providers [config]
  (into {}
        (for [[provider id secret authorize token-url userinfo]
              [["google" (:google-client-id config) (:google-client-secret config)
                "https://accounts.google.com/o/oauth2/v2/auth" "https://oauth2.googleapis.com/token"
                "https://openidconnect.googleapis.com/v1/userinfo"]
               ["unipi" (:wso2-client-id config) (:wso2-client-secret config)
                (:wso2-authorize-url config) (:wso2-token-url config) (:wso2-userinfo-url config)]]
              :when (and id secret (:auth-secret config))]
          [provider {:client-id id :client-secret secret :authorize authorize :token token-url :userinfo userinfo}])))

(defn profile-data
  "Identità dall'endpoint userinfo: subject, email e nome, con i claim legacy di WSO2."
  [provider profile]
  (let [claim (fn [k legacy]
                (or (get profile k)
                    (when (= provider "unipi") (get profile (str "http://wso2.org/claims/" legacy)))))
        subject (claim "sub" "unipiUser")
        email (claim "email" "emailaddress")
        name (or (claim "name" "fullname")
                 (str/join " " (filter string? [(claim "given_name" "givenname") (claim "family_name" "lastname")])))
        name (if (and (string? name) (seq name)) name email)]
    (when-not (and (string? subject) (seq subject) (<= (count subject) 255))
      (throw (ex-info "Subject non valido" {::profile true})))
    (when-not (and (string? email) (seq email) (<= (count email) 320))
      (throw (ex-info "Email non valida" {::profile true})))
    (when (and (= provider "google") (not (true? (get profile "email_verified"))))
      (throw (ex-info "Email non verificata" {::profile true})))
    [subject email (subs name 0 (min 200 (count name)))]))

;; ---------------------------------------------------------------------------
;; HTTP verso il provider (sostituibile nei test)

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 15)) .build)))

(defn- form-encode [params]
  (str/join "&" (for [[k v] params]
                  (str (URLEncoder/encode (name k) StandardCharsets/UTF_8) "=" (URLEncoder/encode (str v) StandardCharsets/UTF_8)))))

(defn- send-json [^HttpRequest request]
  (let [response (.send ^HttpClient @http-client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "Risposta HTTP non riuscita" {::oauth true :status (.statusCode response)})))
    (json/read-value (.body response))))

(defn exchange-code
  "Scambia il codice con il token (client_secret_basic, PKCE) e legge il profilo."
  [{:keys [client-id client-secret token userinfo]} code verifier redirect-uri]
  (let [basic (.encodeToString (Base64/getEncoder)
                               (.getBytes (str (URLEncoder/encode ^String client-id StandardCharsets/UTF_8) ":"
                                               (URLEncoder/encode ^String client-secret StandardCharsets/UTF_8))
                                          StandardCharsets/UTF_8))
        tokens (send-json (-> (HttpRequest/newBuilder (URI. token))
                              (.timeout (Duration/ofSeconds 15))
                              (.header "Authorization" (str "Basic " basic))
                              (.header "Content-Type" "application/x-www-form-urlencoded")
                              (.header "Accept" "application/json")
                              (.POST (HttpRequest$BodyPublishers/ofString
                                      (form-encode {:grant_type "authorization_code" :code code
                                                    :redirect_uri redirect-uri :code_verifier verifier})))
                              .build))
        access-token (get tokens "access_token")]
    (when-not (and (string? access-token) (seq access-token))
      (throw (ex-info "Token mancante" {::oauth true})))
    ;; L'identità proviene esclusivamente dall'endpoint HTTPS userinfo autenticato;
    ;; gli eventuali ID token non vengono decodificati né usati come prova d'identità.
    (send-json (-> (HttpRequest/newBuilder (URI. userinfo))
                   (.timeout (Duration/ofSeconds 15))
                   (.header "Authorization" (str "Bearer " access-token))
                   (.header "Accept" "application/json")
                   .GET .build))))

;; ---------------------------------------------------------------------------
;; Utenti e sessioni

(defn- now [] (Date.))

(defn user-data [user]
  (when user
    {:id (str (:user/id user)) :name (:user/name user) :email (:user/email user) :provider (:user/provider user)}))

(defn current-user [conn token]
  (when (and token (<= (count token) 128))
    (some-> (d/q '[:find (pull ?u [:user/id :user/name :user/email :user/provider])
                   :in $ ?hash ?now
                   :where [?s :session/token-hash ?hash] [?s :session/expires-at ?exp]
                   [(> ?exp ?now)] [?s :session/user ?u]]
                 (d/db conn) (digest token) (now))
            ffirst
            user-data)))

(defn save-login
  "Crea o aggiorna l'utente e apre una nuova sessione; restituisce il token in chiaro."
  [conn provider profile old-token]
  (let [[subject email name] (profile-data provider profile)
        session-token (token 32)]
    ;; Un login concorrente della stessa identità può vincere l'inserimento iniziale.
    (loop [attempt 0]
      (let [result
            (try
              (let [db (d/db conn)
                    t (now)
                    user (ffirst (d/q '[:find ?u :in $ ?p ?s :where [?u :user/provider ?p] [?u :user/subject ?s]]
                                      db provider subject))
                    expired (d/q '[:find ?s :in $ ?now :where [?s :session/expires-at ?e] [(<= ?e ?now)]] db t)
                    old (when old-token
                          (ffirst (d/q '[:find ?s :in $ ?h :where [?s :session/token-hash ?h]] db (digest old-token))))]
                (d/transact conn
                            {:tx-data (concat
                                       [(cond-> {:db/id (or user "user") :user/email email :user/name name :user/last-login t}
                                          (nil? user) (assoc :user/id (random-uuid) :user/provider provider
                                                             :user/subject subject :user/created-at t))
                                        {:session/token-hash (digest session-token)
                                         :session/user (or user "user")
                                         :session/expires-at (Date/from (.plusSeconds (Instant/now) lifetime-seconds))}]
                                       (for [s (distinct (cons old (map first expired))) :when s]
                                         [:db/retractEntity s]))})
                session-token)
              (catch clojure.lang.ExceptionInfo error
                (if (and (zero? attempt) (db/unique-conflict? error)) ::retry (throw error))))]
        (if (= result ::retry) (recur (inc attempt)) result)))))

(defn revoke [conn token]
  (when-let [s (ffirst (d/q '[:find ?s :in $ ?h :where [?s :session/token-hash ?h]] (d/db conn) (digest token)))]
    (d/transact conn {:tx-data [[:db/retractEntity s]]})))

;; ---------------------------------------------------------------------------
;; Handler Ring

(defn- json-response [status body & {:as headers}]
  {:status status
   :headers (merge {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"} headers)
   :body (json/write-value-as-string body)})

(defn- redirect [status location & [cookies]]
  (cond-> {:status status :headers {"Location" location "Cache-Control" "no-store"} :body ""}
    cookies (assoc :cookies cookies)))

(defn- cookie [config value max-age]
  {:value value :max-age max-age :path "/" :http-only true :same-site :lax
   :secure (str/starts-with? (:auth-base-url config) "https://")})

(defn denied [status message] (json-response status {:detail message}))

(defn check-origin
  "JSON + intestazione personalizzata impediscono l'invio tramite form cross-site.
  Restituisce una risposta di errore oppure nil."
  [config request]
  (let [origin (get-in request [:headers "origin"])]
    (cond
      (not= "1" (get-in request [:headers "x-gradient-request"])) (denied 403 "Richiesta non valida.")
      (and origin (not= origin (:auth-base-url config))) (denied 403 "Origine non consentita."))))

(defn request-token [request]
  (get-in request [:cookies session-cookie :value]))

(defn session-info [{:keys [config conn]} request]
  (json-response 200 {:user (current-user conn (request-token request))
                      :providers (vec (keys (providers config)))}))

(defn login [{:keys [config secret]} request]
  (if-let [provider-config (get (providers config) (get-in request [:path-params :provider]))]
    (let [provider (get-in request [:path-params :provider])
          verifier (token 48)
          state (token 24)
          url (str (:authorize provider-config) "?"
                   (form-encode {:response_type "code" :client_id (:client-id provider-config)
                                 :redirect_uri (str (:auth-base-url config) "/auth/callback/" provider)
                                 :scope "openid email profile" :state state
                                 :code_challenge (base64url (sha256 verifier)) :code_challenge_method "S256"}))]
      (redirect 302 url {oauth-cookie (cookie config (sign secret {:provider provider :state state :verifier verifier} oauth-seconds) oauth-seconds)}))
    (denied 404 "Provider non configurato.")))

(defn callback [{:keys [config conn secret exchange]} request]
  (let [provider (get-in request [:path-params :provider])
        provider-config (get (providers config) provider)
        pending (unsign secret (get-in request [:cookies oauth-cookie :value]))
        {:strs [state code error]} (:query-params request)
        clear {oauth-cookie (cookie config "" 0)}
        failed (redirect 303 "/?auth_error=1" clear)]
    (if (or (nil? provider-config) (not= provider (:provider pending)) (str/blank? state)
            (not (same? state (:state pending))) (str/blank? code) error)
      failed
      (try
        (let [profile ((or exchange exchange-code) provider-config code (:verifier pending)
                       (str (:auth-base-url config) "/auth/callback/" provider))
              _ (when-not (map? profile) (throw (ex-info "Profilo non valido" {::profile true})))
              session-token (save-login conn provider profile (request-token request))]
          (redirect 303 "/" (assoc clear session-cookie (cookie config session-token lifetime-seconds))))
        (catch Exception e
          (if (or (::oauth (ex-data e)) (::profile (ex-data e)) (instance? java.io.IOException e)
                  (instance? com.fasterxml.jackson.core.JacksonException e))
            (do (log/warn (str "Login " provider " non riuscito: risposta OAuth o profilo non valido."))
                failed)
            (throw e)))))))

(defn logout [{:keys [config conn]} request]
  (or (check-origin config request)
      (do (some->> (request-token request) (revoke conn))
          (assoc (json-response 200 {:ok true})
                 :cookies {session-cookie (cookie config "" 0) oauth-cookie (cookie config "" 0)}))))
