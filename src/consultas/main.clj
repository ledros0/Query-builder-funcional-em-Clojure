(ns consultas.main
  (:require [clojure.string :as str]
            [consultas.core :as core]))

(defn- processar
  "Recebe uma linha e devolve o texto a imprimir. Nunca lança exceção."
  [dados linha]
  (try
    (let [linha (str/trim linha)]
      (cond
        (str/blank? linha) "ERRO: comando vazio"
        (str/starts-with? linha "QUERY ")
        (let [ast (core/analisar (subs linha 6))
              res (core/executar ast dados)]
          ;; aqui entra a formatação do resultado
          (pr-str res))
        :else (str "ERRO: comando desconhecido: " linha)))
    (catch Exception e
      (str "ERRO: " (.getMessage e)))))

(defn -main [& args]
  (let [dados (core/ler-csv (slurp (first args)))]
    (loop []
      (print "> ") (flush)
      (when-let [linha (read-line)]
        (when-not (= "EXIT" (str/trim linha))
          (println (processar dados linha))
          (recur))))))