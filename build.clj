(ns build
  "Uberjar del backend: `clojure -T:uberjar uber`."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/gradient.jar")

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    ;; Compilazione AOT: avvio più rapido e meno memoria (niente compilazione a runtime).
    (b/compile-clj {:basis basis :ns-compile '[gradient.main] :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file jar-file :basis basis :main 'gradient.main})))
