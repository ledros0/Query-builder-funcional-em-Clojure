Aline Flores Douaki  
Guilherme Biribilli  
Mateus Borba Silveira  


# Query builder funcional em Clojure

Motor de consultas sobre um arquivo CSV, escrito em Clojure. A consulta é escrita em texto, transformada em **dado** (uma AST feita só de mapas, vetores e palavras-chave), depois **compilada** em funções, que são **compostas** e aplicadas aos registros. Não há estado mutável em nenhum ponto do caminho.

```
texto  ──analisar──▶  AST (dado)  ──conferir──▶  erros?  ──compilar──▶  funções  ──comp──▶  resultado
```

## Como executar

Requisitos: [Clojure CLI](https://clojure.org/guides/install_clojure). Nenhuma outra dependência.

```
clojure -M:run dados/filmes.csv
```

O programa carrega o CSV e abre um terminal interativo:

```
> QUERY onde genero = "drama" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota
Cidade de Deus; 8.60
Ainda Estou Aqui; 8.20
Central do Brasil; 8.00
> QUERY onde genero = "drama" | media duracao
127.40
> EXIT
```

| Comando | Efeito |
|---|---|
| `QUERY <consulta>` | Executa a consulta e imprime o resultado. |
| `EXIT` | Encerra o programa (o fim da entrada também encerra). |

Qualquer entrada inválida (comando desconhecido, consulta malformada, campo inexistente, linha em branco) produz uma linha `ERRO: ...` e o prompt volta.

### Formato do CSV

Separador `;`, sem aspas. A primeira linha declara nome e tipo de cada campo (`inteiro`, `decimal` ou `texto`):

```
titulo:texto;ano:inteiro;genero:texto;nota:decimal;duracao:inteiro
```

O motor **não conhece nenhum esquema**: os nomes e tipos dos campos vêm sempre do cabeçalho.

## Linguagem de consulta

```
consulta  := [ elemento [| elemento]* ]
elemento  := estagio | final

estagio   := onde <expr> | ordenar por <campo> [desc] | limitar <n>
final     := selecionar <campo> [, <campo>]*
           | contar | soma <campo> | media <campo>
           | agrupar por <campo> com <agregacao>
agregacao := contar | soma <campo> | media <campo>

expr      := expr ou expr | expr e expr | nao expr | ( expr ) | <campo> <op> <literal>
op        := = | != | < | <= | > | >=
literal   := inteiro | decimal | "texto"
```

Precedência, da maior para a menor: `nao`, `e`, `ou`.

## A forma da AST

Uma consulta é um mapa com dois campos. A AST é **dado puro**: não contém funções, pode ser impressa, comparada com `=` e escrita à mão.

```clojure
{:estagios [<estagio> ...]   ; vetor, na ordem em que aparecem no texto
 :terminal <terminal> | nil}
```

### Estágios (`:estagios`)

| Estágio | Forma |
|---|---|
| `onde <expr>` | `[:onde <expr>]` |
| `ordenar por c [desc]` | `[:ordenar-por :c :asc]` ou `[:ordenar-por :c :desc]` |
| `limitar n` | `[:limitar n]` |
| `selecionar a, b` | `[:selecionar [:a :b]]` (tem que ser o último elemento) |

### Expressões (`<expr>`)

| Expressão | Forma |
|---|---|
| comparação | `[<op> :campo literal]`, com `<op>` em `:=` `:!=` `:<` `:<=` `:>` `:>=` |
| e | `[:e <expr> <expr>]` |
| ou | `[:ou <expr> <expr>]` |
| nao | `[:nao <expr>]` |

Literais são `Long` (inteiro), `Double` (decimal) ou `String` (texto).

### Terminal (`:terminal`)

| Elemento final | Forma |
|---|---|
| nenhum | `nil` |
| `contar` | `[:contar]` |
| `soma c` | `[:soma :c]` |
| `media c` | `[:media :c]` |
| `agrupar por c com <agregacao>` | `[:agrupar :c <agregacao>]`, com `<agregacao>` sendo `[:contar]`, `[:soma :campo]` ou `[:media :campo]` |

### Exemplo

Texto:

```
onde genero = "drama" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota
```

AST:

```clojure
{:estagios [[:onde [:e [:= :genero "drama"] [:>= :nota 8]]]
            [:ordenar-por :nota :desc]
            [:selecionar [:titulo :nota]]]
 :terminal nil}
```

Com terminal:

```clojure
;; agrupar por genero com media duracao
{:estagios []
 :terminal [:agrupar :genero [:media :duracao]]}
```

### AST com nó desconhecido (para o teste de falha)

```clojure
{:estagios [[:inverter]]
 :terminal nil}
```

Executada com `executar`, essa AST falha com `No matching clause: :inverter`, nomeando o nó.

## O builder

O `consultas.core` expõe funções que **recebem uma consulta e devolvem outra**, sem alterar a original:

```clojure
(def base    (consulta))
(def dramas  (onde base [:= :genero "drama"]))
(def dois    (limitar dramas 2))
(def tres    (limitar dramas 3))   ; dramas não foi alterada por dois

(executar dois dados)
```

| Função | Efeito |
|---|---|
| `(consulta)` | Consulta nova, sem estágios. |
| `(onde c expr)` | Acrescenta um filtro. |
| `(selecionar c campos)` | Acrescenta a projeção (último elemento). |
| `(ordenar-por c campo ordem)` | `ordem` é `:asc` ou `:desc`. |
| `(limitar c n)` | Acrescenta um limite. |
| `(analisar texto)` | Texto (sem a palavra `QUERY`) → AST. Erro de sintaxe vira `ex-info`. |
| `(executar c dados)` | `dados` é `{:esquema [[campo tipo] ...] :registros seq}`. Devolve os registros, o valor do terminal ou `{:erros [...]}`. |

O analisador e o builder produzem o mesmo dado:
`(analisar "onde genero = \"drama\" | limitar 2")` é igual, por `=`, a `dois` acima.

## Os namespaces

| Namespace | Papel |
|---|---|
| `consultas.main` | Único com I/O. Laço do terminal (`loop`/`recur`), leitura do arquivo e **formatação da saída** (`; ` entre valores, decimais com duas casas e ponto, `(vazio)`, linhas `ERRO:`). |
| `consultas.sintaxe` | Texto → AST. Tokenizador e analisador recursivo descendente: cada função recebe os *tokens* e devolve `[nó, tokens que sobraram]`, sem guardar posição de leitura. |
| `consultas.compilador` | `conferir`: valida a AST contra o esquema antes de executar (campo inexistente, tipos incompatíveis, `soma`/`media` em campo não numérico, `selecionar` fora do fim). `compilar-*` e `rodar`: AST → funções → resultado. |
| `consultas.core` | Texto do CSV → registros (`ler-csv`), o builder e `executar`. É o namespace usado nos testes. |

## Decisões de projeto (o lado funcional)

1. **A consulta é dado.** A AST usa só mapas, vetores e palavras-chave. Código é dado.
2. **Análise recursiva e sem estado.** `expr` contém `expr`, e o analisador também: `parse-ou` → `parse-e` → `parse-nao` → `parse-primario`. Listas de operandos usam `loop`/`recur`, não variáveis.
3. **Compilar, não interpretar.** A AST é percorrida uma única vez. Cada `onde` vira um predicado, e cada estágio vira uma função de sequência em sequência. Nenhuma inspeção da AST ocorre por registro.
4. **`case` sem padrão.** `compilar-expr`, `compilar-estagio` e `compilar-terminal` despacham com `case` sobre o tipo do nó, **sem** expressão padrão. Um nó inexistente na linguagem falha com `No matching clause: <nó>`.
5. **A consulta é uma composição.** Os estágios compilados são combinados com `reduce` sobre `identity`, usando `comp` na ordem certa, de modo que a consulta lê da esquerda para a direita.
6. **Aplicação parcial.** `limitar 3` vira `(partial take 3)`; `ordenar por` vira `(partial sort-by ...)`; `selecionar` vira `(partial map ...)`; cada comparação de um `onde` fixa o campo e o literal.
7. **O builder devolve consultas novas.** As funções do builder usam `update` e nunca alteram a consulta de partida.
8. **Agregar é dobrar.** `contar`, `soma` e `media` usam `reduce`; `agrupar por` usa `group-by` e reaproveita a mesma dobra em cada grupo.
9. **Conferência antes da execução.** `conferir` roda contra o esquema e uma consulta inválida é recusada sem processar nenhum registro.

## Pureza

Em todo o projeto não são usados `atom`, `ref`, `agent`, `volatile!`, `transient`, `set!`, coleções mutáveis de Java, `eval` nem `read-string`. `doseq`, `dotimes` e `while` não aparecem. O laço do terminal é recursão (`loop`/`recur`). Nenhuma biblioteca além do próprio Clojure.

## Estrutura do repositório

```
.
├── deps.edn
├── README.md
├── casos_teste_ep02.txt
├── dados/
│   └── filmes.csv
└── src/consultas/
    ├── main.clj
    ├── sintaxe.clj
    ├── compilador.clj
    └── core.clj
```
