(ns consultas.core
  (:require [clojure.string :as str]
            [consultas.sintaxe :as sintaxe]
            [consultas.compilador :as compilador]))

(defn selecionar [c campos]
  (update c :estagios conj [:selecionar campos]))

;;                                     Texto -> registros
(defn- converter [tipo s]
  (case tipo
    :inteiro (Long/parseLong s)
    :decimal (Double/parseDouble s)
    :texto   s))

(defn ler-csv [texto]
  (let [[cab & linhas] (remove str/blank? (str/split-lines texto))
        esquema (mapv (fn [col]
                        (let [[nome tipo] (str/split col #":")]
                          [(keyword nome) (keyword tipo)]))
                      (str/split cab #";"))
        registro (fn [linha]
                   (zipmap (map first esquema)
                           (map (fn [[_ t] v] (converter t v))
                                esquema
                                (str/split linha #";"))))]
    {:esquema esquema
     :registros (map registro linhas)}))


;;                                        Builder: consulta ->
(defn consulta []
  {:estagios [] :terminal nil})

(defn onde [c expr]
  (update c :estagios conj [:onde expr]))

(defn selecionar [c campos]
  (update c :estagios conj [:selecionar campos]))

(defn ordenar-por [c campo ordem]
  (update c :estagios conj [:ordenar-por campo ordem]))

(defn limitar [c n]
  (update c :estagios conj [:limitar n]))


;;                                  Texto -> ATS
(defn analisar [texto]
  (sintaxe/analisar texto))

;;                                             Executar

(defn executar [c dados]
  (let [erros (compilador/conferir c (:esquema dados))]
    (if (seq erros)
      {:erros erros}
      (compilador/rodar c (:registros dados)))))