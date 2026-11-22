(ns gradient.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [datomic.client.api :as d]
            [gradient.auth :as auth]
            [gradient.config :as config]
            [gradient.test-support :refer [body gql login! request test-config test-system]])
  (:import (java.net URI URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.util Date)))

(def providers-config
  {:google-client-id "google-id" :google-client-secret "google-secret"
   :wso2-client-id "unipi-id" :wso2-client-secret "unipi-secret"})

(defn query-params [url]
  (into {} (for [pair (str/split (.getRawQuery (URI. url)) #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [k (URLDecoder/decode v StandardCharsets/UTF_8)])))

(defn set-cookie [response name]
  (some #(when (str/starts-with? % (str name "=")) (second (re-find #"^[^=]+=([^;]*)" %)))
        (get-in response [:headers "Set-Cookie"])))

(deftest anonymous-and-csrf-are-rejected
  (let [system (test-system)
        token (login! system)]
    (is (= 401 (:status (request system :post "/graphql" :body {:query "{ me { id } }"}))))
    (is (= 401 (:status (request system :post "/graphql" :token "inventato" :body {:query "{ me { id } }"}))))
    (is (= 403 (:status (request system :post "/graphql" :token token :body {:query "{ me { id } }"}
                                 :headers {"x-gradient-request" "0"}))))
    (is (= 403 (:status (request system :post "/graphql" :token token :body {:query "{ me { id } }"}
                                 :headers {"origin" "https://evil.example"}))))
    (is (= 200 (:status (request system :post "/graphql" :token token :body {:query "{ me { id } }"}
                                 :headers {"origin" "http://127.0.0.1:5173"}))))
    (is (= 403 (:status (request system :post "/auth/logout" :token token :headers {"x-gradient-request" nil}))))
    (is (= {:status "ok"} (body (request system :get "/healthz"))))))

(deftest oauth-redirect-pkce-and-invalid-state
  (doseq [provider ["google" "unipi"]]
    (let [system (apply test-system (mapcat identity providers-config))
          response (request system :get (str "/auth/login/" provider))
          params (query-params (get-in response [:headers "Location"]))
          state-cookie (set-cookie response auth/oauth-cookie)]
      (is (= 302 (:status response)))
      (is (= "S256" (params "code_challenge_method")))
      (is (= "openid email profile" (params "scope")))
      (is (= (str "http://127.0.0.1:5173/auth/callback/" provider) (params "redirect_uri")))
      (is (seq state-cookie))
      (testing "state errato o assente"
        (doseq [state ["sbagliato" ""]]
          (let [callback (request system :get (str "/auth/callback/" provider)
                                  :headers {"cookie" (str auth/oauth-cookie "=" state-cookie)}
                                  :query-string (str "code=abc&state=" state))]
            (is (= "/?auth_error=1" (get-in callback [:headers "Location"]))))))
      (is (= 404 (:status (request system :get "/auth/login/altro")))))))

(deftest google-and-legacy-unipi-profiles
  (is (= ["g-1" "a@example.invalid" "Ada Lovelace"]
         (auth/profile-data "google" {"sub" "g-1" "email" "a@example.invalid" "email_verified" true "name" "Ada Lovelace"})))
  (is (thrown? Exception (auth/profile-data "google" {"sub" "g-1" "email" "a@example.invalid"})))
  (is (= ["u-1" "b@example.invalid" "Bruno Rossi"]
         (auth/profile-data "unipi" {"http://wso2.org/claims/unipiUser" "u-1"
                                     "http://wso2.org/claims/emailaddress" "b@example.invalid"
                                     "http://wso2.org/claims/givenname" "Bruno"
                                     "http://wso2.org/claims/lastname" "Rossi"})))
  (is (= "c@example.invalid" (last (auth/profile-data "unipi" {"sub" "u-2" "email" "c@example.invalid"}))))
  (is (thrown? Exception (auth/profile-data "google" {"sub" "" "email" "x" "email_verified" true}))))

(deftest auth-configuration
  (let [load #(config/load-config % "/nonexistent/.env")]
    (are [env] (thrown? clojure.lang.ExceptionInfo (load env))
      {"AUTH_BASE_URL" "https://gradient.example.org/percorso"}
      {"AUTH_BASE_URL" "http://gradient.example.org"}
      {"AUTH_SECRET" "corto"}
      {"GOOGLE_CLIENT_ID" "id"}
      {"WSO2_TOKEN_URL" "http://iam.example/token"})
    (is (= "https://gradient.example.org" (:auth-base-url (load {"AUTH_BASE_URL" "https://gradient.example.org/"}))))
    (is (empty? (auth/providers (load {}))))))

(deftest callback-exchanges-code-and-reads-userinfo
  (let [calls (atom [])
        system (assoc (apply test-system (mapcat identity providers-config))
                      :exchange (fn [provider code verifier redirect-uri]
                                  (swap! calls conj [(:client-id provider) code verifier redirect-uri])
                                  {"sub" "g-42" "email" "doc@example.invalid" "email_verified" true "name" "Docente"}))
        login (request system :get "/auth/login/google")
        state ((query-params (get-in login [:headers "Location"])) "state")
        callback (request system :get "/auth/callback/google"
                          :headers {"cookie" (str auth/oauth-cookie "=" (set-cookie login auth/oauth-cookie))}
                          :query-string (str "code=codice&state=" state))
        token (set-cookie callback auth/session-cookie)]
    (is (= 303 (:status callback)))
    (is (= "/" (get-in callback [:headers "Location"])))
    (is (= [["google-id" "codice"]] (map #(subvec % 0 2) @calls)))
    (is (= 64 (count (nth (first @calls) 2))) "verifier PKCE di 48 byte")
    (is (some #(and (str/includes? % "HttpOnly") (str/includes? % "SameSite=Lax"))
              (get-in callback [:headers "Set-Cookie"])))
    (is (= {:name "Docente" :email "doc@example.invalid" :provider "google"}
           (-> (gql system token "{ me { name email provider } }") :data :me)))
    (testing "un profilo non valido non crea sessioni"
      (let [system (assoc system :exchange (constantly {"sub" "x" "email" "y"}))
            login (request system :get "/auth/login/google")
            state ((query-params (get-in login [:headers "Location"])) "state")
            callback (request system :get "/auth/callback/google"
                              :headers {"cookie" (str auth/oauth-cookie "=" (set-cookie login auth/oauth-cookie))}
                              :query-string (str "code=codice&state=" state))]
        (is (= "/?auth_error=1" (get-in callback [:headers "Location"])))))))

(deftest real-sessions-ownership-logout-and-expiry
  (let [system (test-system)
        alice (login! system "alice")
        bob (login! system "bob")
        course (get-in (gql system alice "mutation { createCourse(input: {name: \"Analisi\", academicYear: \"2026/27\"}) { course { id } } }")
                       [:data :createCourse :course :id])]
    (testing "isolamento dei corsi per proprietario"
      (is (= [{:id course}] (get-in (gql system alice "{ courses { id } }") [:data :courses])))
      (is (= [] (get-in (gql system bob "{ courses { id } }") [:data :courses])))
      (is (= "NOT_FOUND" (get-in (gql system bob "query($id: ID!) { courseTables(courseId: $id) { id } }" {:id course})
                                 [:errors 0 :extensions :code])))
      (is (= "NOT_FOUND" (get-in (gql system bob (str "mutation { createTable(input: {courseId: \"" course "\", name: \"X\"}) { errors { code } } }"))
                                 [:data :createTable :errors 0 :code]))))
    (testing "lo stesso utente al secondo login non viene duplicato"
      (login! system "alice")
      (is (= 2 (count (d/q '[:find ?u :where [?u :user/id]] (d/db (:conn system)))))))
    (testing "logout revoca la sessione"
      (is (= 200 (:status (request system :post "/auth/logout" :token bob))))
      (is (= 401 (:status (request system :post "/graphql" :token bob :body {:query "{ me { id } }"})))))
    (testing "sessione scaduta"
      (d/transact (:conn system) {:tx-data [{:session/token-hash (auth/digest alice) :session/expires-at (Date. 0)}]})
      (is (= 401 (:status (request system :post "/graphql" :token alice :body {:query "{ me { id } }"}))))
      (is (nil? (:user (body (request system :get "/auth/session" :token alice))))))))

(deftest signed-state-cookie
  (let [secret (apply str (repeat 40 "k"))
        value (auth/sign secret {:state "s"} 60)]
    (is (= {:state "s"} (auth/unsign secret value)))
    (is (nil? (auth/unsign (str secret "x") value)))
    (is (nil? (auth/unsign secret (str value "x"))))
    (is (nil? (auth/unsign secret (auth/sign secret {:state "s"} -1))))))
