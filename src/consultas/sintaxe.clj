(ns consultas.sintaxe 
  (:require
    [clojure.string :as str]))


(defn tokenizar [texto]
  (re-seq #"\"[^\"]*\"|<=|>=|!=|[=<>(),|]|\d+\.\d+|\d+|[^\s=<>!(),|\"]+" texto))

(defn conversao [token]
  (cond
    (nil? token)
    (throw (ex-info "falta o literal" {}))

    (str/starts-with? token "\"")
    (subs token 1 (dec (count token)))

    (re-matches #"\d+\.\d+" token)
    (Double/parseDouble token)

    (re-matches #"\d+" token)
    (Long/parseLong token)

    :else
    (throw (ex-info (str "literal invalido: " token) {}))))

(def operadores #{"=" "!=" "<" "<=" ">" ">="})

(defn parse-comparacao
  "[campo op literal & sobra] -> [no sobra]"
  [tokens]
  (let [[campo op literal & sobra] tokens]
    (cond
      (nil? campo)
      (throw (ex-info "falta o campo" {}))

      (not (contains? operadores op))
      (throw (ex-info (str "operador desconhecido: " op) {}))

      :else
      [[(keyword op) (keyword campo) (conversao literal)]
       sobra])))

(declare parse-ou)

(defn- erro [msg] (throw (ex-info msg {})))


(defn parse-primario [tokens]
  (let [t (first tokens)]
    (cond
      (nil? t) (erro "expressao incompleta")

      (= t "(")
      (let [[no resto] (parse-ou (rest tokens))]
        (if (= (first resto) ")")
          [no (rest resto)]
          (erro "parentese sem fechar")))

      :else (parse-comparacao tokens))))

(defn parse-nao [tokens]
  (if (= (first tokens) "nao")
    (let [[no resto] (parse-nao (rest tokens))]
      [[:nao no] resto])
    (parse-primario tokens)))

(defn- parse-binario
  "Aplica `proximo` e encadeia operandos separados pela palavra `palavra`."
  [palavra tag proximo tokens]
  (let [[a resto] (proximo tokens)]
    (loop [acc a, resto resto]
      (if (= (first resto) palavra)
        (let [[b resto2] (proximo (rest resto))]
          (recur [tag acc b] resto2))
        [acc resto]))))

(defn parse-e  [tokens] (parse-binario "e"  :e  parse-nao tokens))
(defn parse-ou [tokens] (parse-binario "ou" :ou parse-e   tokens))


(defn- numero [token]
  (if (and token (re-matches #"\d+" token))
    (Long/parseLong token)
    (erro "numero esperado")))

(defn- parse-lista-campos [tokens]
  (loop [campos [], tokens tokens]
    (let [c (first tokens)]
      (when (or (nil? c) (#{"," "|"} c)) (erro "campo esperado"))
      (let [campos (conj campos (keyword c))
            resto  (rest tokens)]
        (if (= (first resto) ",")
          (recur campos (rest resto))
          [campos resto])))))

(defn- campo [token]
  (if (or (nil? token) (#{"," "|" "por" "com"} token))
    (erro "campo esperado")
    (keyword token)))

(defn- parse-agregacao [tokens]
  (case (first tokens)
    "contar" [[:contar] (rest tokens)]
    "soma"   [[:soma  (campo (second tokens))] (drop 2 tokens)]
    "media"  [[:media (campo (second tokens))] (drop 2 tokens)]
    (erro "agregacao esperada (contar, soma ou media)")))

(defn parse-elemento
  "tokens -> [[:estagio no] resto] ou [[:terminal no] resto]"
  [tokens]
  (case (first tokens)
    "onde"
    (let [[e resto] (parse-ou (rest tokens))]
      [[:estagio [:onde e]] resto])

    "ordenar"
    (do (when-not (= (second tokens) "por") (erro "falta \"por\" depois de ordenar"))
        (let [c      (campo (nth tokens 2 nil))
              resto  (drop 3 tokens)]
          (if (= (first resto) "desc")
            [[:estagio [:ordenar-por c :desc]] (rest resto)]
            [[:estagio [:ordenar-por c :asc]] resto])))

    "limitar"
    [[:estagio [:limitar (numero (second tokens))]] (drop 2 tokens)]

    "selecionar"
    (let [[campos resto] (parse-lista-campos (rest tokens))]
       [[:estagio [:selecionar campos]] resto])

    "contar" [[:terminal [:contar]] (rest tokens)]
    "soma"   [[:terminal [:soma  (campo (second tokens))]] (drop 2 tokens)]
    "media"  [[:terminal [:media (campo (second tokens))]] (drop 2 tokens)]

    "agrupar"
    (do (when-not (= (second tokens) "por") (erro "falta \"por\" depois de agrupar"))
        (let [c (campo (nth tokens 2 nil))]
          (when-not (= (nth tokens 3 nil) "com") (erro "falta \"com <agregacao>\""))
          (let [[ag resto] (parse-agregacao (drop 4 tokens))]
            [[:terminal [:agrupar c ag]] resto])))

    nil (erro "estagio vazio")
    (erro (str "estagio desconhecido: " (first tokens)))))

(defn- dividir-por-barra [tokens]
  (loop [grupos [[]], tokens tokens]
    (if (empty? tokens)
      grupos
      (if (= (first tokens) "|")
        (recur (conj grupos []) (rest tokens))
        (recur (conj (pop grupos) (conj (peek grupos) (first tokens)))
               (rest tokens))))))

(defn analisar [texto]
  (let [tokens (tokenizar texto)]
    (if (empty? tokens)
      {:estagios [] :terminal nil}
      (let [elementos (mapv (fn [grupo]
                              (let [[el resto] (parse-elemento grupo)]
                                (when (seq resto)
                                  (erro (str "token inesperado: " (first resto))))
                                el))
                            (dividir-por-barra tokens))
            finais    (filter (fn [[tipo _]] (= tipo :terminal)) elementos)
            ultimo    (last elementos)]
        (when (and (seq finais) (not= (first ultimo) :terminal))
          (erro "o elemento final tem que ser o ultimo da consulta"))
        (when (> (count finais) 1)
          (erro "so pode haver um elemento final"))
        {:estagios (mapv second (remove (fn [[tipo _]] (= tipo :terminal)) elementos))
         :terminal (second (first finais))}))))
