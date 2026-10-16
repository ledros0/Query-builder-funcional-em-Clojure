(ns consultas.compilador)

(defn- numerico? [t] (contains? #{:inteiro :decimal} t))

(defn- tipo-literal [v]
  (cond (integer? v) :inteiro
        (float? v)   :decimal
        (string? v)  :texto))

(defn- compativeis? [tc tl]
  (or (= tc tl) (and (numerico? tc) (numerico? tl))))

(defn- erro-campo [campo tipos]
  (when-not (contains? tipos campo)
    (str "campo inexistente: " (name campo))))

(defn- erro-numerico [campo tipos]
  (or (erro-campo campo tipos)
      (when-not (numerico? (get tipos campo))
        (str "exige campo numerico: " (name campo)))))

(defn- conferir-expr [expr tipos]
  (case (first expr)
    (:e :ou) (into (conferir-expr (nth expr 1) tipos)
                   (conferir-expr (nth expr 2) tipos))
    :nao     (conferir-expr (nth expr 1) tipos)
    (:= :!= :< :<= :> :>=)
    (let [campo (nth expr 1)
          tl    (tipo-literal (nth expr 2))]
      (if-let [e (erro-campo campo tipos)]
        [e]
        (if (compativeis? (get tipos campo) tl)
          []
          [(str "comparacao entre " (name (get tipos campo)) " e "
                (name tl) " no campo " (name campo))])))))

(defn- conferir-estagio [[tipo & args] tipos]
  (case tipo
    :onde        (conferir-expr (first args) tipos)
    :ordenar-por (vec (keep #(erro-campo % tipos) [(first args)]))
    :limitar     []
    :selecionar  (vec (keep #(erro-campo % tipos) (first args)))))

(defn- conferir-agregacao [[tipo campo] tipos]
  (case tipo
    :contar []
    :soma   (vec (keep #(erro-numerico % tipos) [campo]))
    :media  (vec (keep #(erro-numerico % tipos) [campo]))))

(defn- conferir-terminal [terminal tipos]
  (if (nil? terminal)
    []
    (case (first terminal)
      :contar  []
      :soma    (conferir-agregacao terminal tipos)
      :media   (conferir-agregacao terminal tipos)
      :agrupar (into (vec (keep #(erro-campo % tipos) [(nth terminal 1)]))
                     (conferir-agregacao (nth terminal 2) tipos)))))

(defn- conferir-ordem [{:keys [estagios terminal]}]
  (let [tipos  (map first estagios)
        depois (rest (drop-while #(not= % :selecionar) tipos))]
    (cond
      (seq depois) ["selecionar tem que ser o ultimo"]
      (and (some #{:selecionar} tipos) terminal) ["selecionar tem que ser o ultimo"]
      :else [])))

(defn conferir [c esquema]
  (let [tipos (into {} esquema)]
    (vec (concat (conferir-ordem c)
                 (mapcat #(conferir-estagio % tipos) (:estagios c))
                 (conferir-terminal (:terminal c) tipos)))))

(defn- compilar-comparacao [op campo valor]
  (let [teste (case op
                :=  zero?
                :!= (complement zero?)
                :<  neg?
                :<= (complement pos?)
                :>  pos?
                :>= (complement neg?))]
    (fn [registro] (teste (compare (get registro campo) valor)))))

(defn compilar-expr [expr]
  (case (first expr)
    :e   (every-pred (compilar-expr (nth expr 1)) (compilar-expr (nth expr 2)))
    :ou  (some-fn    (compilar-expr (nth expr 1)) (compilar-expr (nth expr 2)))
    :nao (complement (compilar-expr (nth expr 1)))
    (:= :!= :< :<= :> :>=)
    (compilar-comparacao (first expr) (nth expr 1) (nth expr 2))))

(defn compilar-estagio [[tipo & args]]
  (case tipo
    :onde        (partial filter (compilar-expr (first args)))
    :limitar     (partial take (first args))
    :ordenar-por (let [[campo ordem] args
                       cmp (if (= ordem :desc) #(compare %2 %1) compare)]
                   (partial sort-by #(get % campo) cmp))
    :selecionar  (let [campos (first args)]
                   (partial map #(select-keys % campos)))))

(defn- contar [regs] (reduce (fn [n _] (inc n)) 0 regs))
(defn- somar  [campo regs] (reduce + 0 (map #(get % campo) regs)))
(defn- media-de [campo regs]
  (let [n (contar regs)]
    (if (zero? n) 0.0 (/ (somar campo regs) (double n)))))

(defn- compilar-agregacao [[tipo campo]]
  (case tipo
    :contar contar
    :soma   (partial somar campo)
    :media  (partial media-de campo)))

(defn compilar-terminal [terminal]
  (if (nil? terminal)
    identity
    (case (first terminal)
      :contar  contar
      :soma    (compilar-agregacao terminal)
      :media   (compilar-agregacao terminal)
      :agrupar (let [campo (nth terminal 1)
                     agg   (compilar-agregacao (nth terminal 2))]
                 (fn [regs]
                   (into (sorted-map)
                         (map (fn [[k grupo]] [k (agg grupo)]))
                         (group-by #(get % campo) regs)))))))

(defn rodar [c registros]
  (let [pipeline (reduce (fn [acc est] (comp (compilar-estagio est) acc))
                         identity
                         (:estagios c))
        final    (compilar-terminal (:terminal c))]
    (final (pipeline registros))))