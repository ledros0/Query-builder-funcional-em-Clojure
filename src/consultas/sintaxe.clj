(ns consultas.sintaxe)


(defn tokenizar [texto]
  (re-seq #"\"[^\"]*\"|<=|>=|!=|[=<>(),|]|\d+\.\d+|\d+|[^\s=<>!(),|\"]+" texto))

(defn analisar [texto]
  {:estagios [] :terminal nil})