(ns consultas.main
  (:require [clojure.string :as str]
            [consultas.core :as core]))

(defn- fmt
  "Inteiro e texto saem como estao; decimal sai com 2 casas e PONTO."
  [v]
  (if (float? v)
    (String/format java.util.Locale/US "%.2f" (to-array [v]))
    (str v)))

(defn- colunas
  "Colunas da saida: as do selecionar, ou as do esquema (na ordem do arquivo)."
  [ast esquema]
  (or (some (fn [[tipo campos]] (when (= tipo :selecionar) campos))
            (:estagios ast))
      (map first esquema)))

(defn- linha-registro [cols registro]
  (str/join "; " (map #(fmt (get registro %)) cols)))


(defn- erros [res]
  (when (and (map? res) (not (sorted? res)))
    (:erros res)))

(defn- formatar
  "AST + dados + resultado do executar -> texto a imprimir."
  [ast dados res]
  (cond
    (seq (erros res))
    (str "ERRO: " (first (:erros res)))
    
    (nil? (:terminal ast))
    (if (empty? res)
      "(vazio)"
      (let [cols (colunas ast (:esquema dados))]
        (str/join "\n" (map #(linha-registro cols %) res))))
    
    (= :agrupar (first (:terminal ast)))
    (if (empty? res)
      "(vazio)"
      (str/join "\n" (map (fn [[k v]] (str (fmt k) "; " (fmt v))) res)))
    
    :else (fmt res)))


(defn- processar [dados linha]
  (try
    (let [linha (str/trim linha)]
      (cond
        (str/blank? linha) "ERRO: comando vazio"

        (or (= linha "QUERY") (str/starts-with? linha "QUERY "))
        (let [ast (core/analisar (subs linha 5))
              res (core/executar ast dados)]
          (formatar ast dados res))

        :else (str "ERRO: comando desconhecido: " linha)))
    (catch Exception e
      (str "ERRO: " (.getMessage e)))))


(defn- carregar [caminho]
  (try
    (core/ler-csv (str/replace (slurp caminho :encoding "UTF-8") #"^\uFEFF" ""))
    (catch Exception e
      (println (str "ERRO: nao foi possivel carregar o arquivo: " (.getMessage e)))
      nil)))

(defn- terminal [dados]
  (loop []
    (print "> ")
    (flush)
    (when-let [linha (read-line)]
      (when-not (= "EXIT" (str/trim linha))
        (println (processar dados linha))
        (recur)))))

(defn -main [& args]
  (if-let [caminho (first args)]
    (when-let [dados (carregar caminho)]
      (terminal dados))
    (println "uso: clojure -M:run <arquivo.csv>")))