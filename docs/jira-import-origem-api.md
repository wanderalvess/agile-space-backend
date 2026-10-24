# Importação do Jira na Review: de onde vem cada texto (migração V93)

Hoje a importação preenche Problema, Solução e Critérios de aceite com texto que o navegador deduz por regex, sem dizer de
onde veio. Este contrato acrescenta:

1. **`POST /api/jira/text-sources`**: dado o texto cru das issues, diz de onde pode vir cada campo e o que entra sozinho.
   A regra (antes só no navegador) passa a morar aqui, com testes.
2. **`origins` no card da Review**: o card guarda de onde veio o texto de cada campo e o que o Jira tinha, para o selo de
   origem e o "Restaurar do Jira" depois de uma edição.

Tudo é **aditivo e retrocompatível**: cliente que não conhece os dois continua funcionando como antes (o frontend novo cai na
regra local se a rota devolver 404). Rotas exigem o JWT. JSON camelCase. Erros no formato padrão `{ "error", "message" }`.

## 1. `POST /api/jira/text-sources`

O servidor **não chama o Jira**: o token continua no navegador. Recebe o texto que o navegador já buscou e devolve a
classificação. Até 200 issues por pedido.

```ts
interface Request {
  issues: Issue[];
}
interface Issue {
  key: string;
  type?: string;            // "História", "Codificação"...: define quais subtarefas valem
  title?: string;
  description?: string;     // texto puro (o navegador achata o ADF)
  criteriaField?: string;   // campo "Critérios de aceite" do Jira, quando existir
  comments?: { body: string; author?: string; created?: string }[]; // created como o Jira escreve: "2026-10-14T10:00:00.000-0300"
  subtasks?: Issue[];       // um nível, mesmo formato
  parent?: Issue;           // um nível, mesmo formato
}

interface Response {
  issues: {                 // mesma ordem do pedido
    key: string;
    problem: Field;
    solution: Field;
    acceptanceCriteria: Field;
  }[];
}
interface Field {
  confidence: 'certa' | 'revisar' | 'vazio';
  sourceId?: string;        // fonte que entra sozinha; ausente = o campo fica vazio até a pessoa escolher
  candidates: { id: string; kind: 'campo' | 'comentario' | 'descricao' | 'subtarefa' | 'pai'; label: string; text: string; explicit: boolean }[];
}
```

### Regra

- Uma fonte é **explícita** quando o texto veio de um título conhecido ("Problema", "Contexto", "Solução", "Critérios de
  aceite"... com ou sem emoji, negrito ou `h2.`) ou de um campo do Jira. É **palpite** quando só foi deduzida: o começo da
  descrição como Problema, linhas em Gherkin ou lista como Critérios.
- **`certa`**: exatamente uma fonte explícita (texto repetido em duas fontes conta uma). `sourceId` aponta para ela.
- **`revisar`**: só palpite, ou mais de uma fonte explícita com textos diferentes. Não há `sourceId`: **o campo fica vazio**
  até a pessoa escolher uma das `candidates` ou deixar vazio.
- **`vazio`**: nada confiável.
- Subtarefas e issue pai só entram quando a própria issue **não** tem texto explícito para o campo. Entre as subtarefas, as
  de codificação/desenvolvimento (tipo ou título com "codifica", "desenvolv", "dev", "coding") valem sozinhas quando existem.
- **Palpite limitado:** o texto de um palpite (início da descrição, linhas de teste) é encurtado para até 600 caracteres: os
  primeiros parágrafos que cabem; se o primeiro já passa disso, corta no fim de uma frase ou numa palavra e termina em "…".
  Fonte explícita não é cortada.
- **Marcação do Jira:** todo texto devolvido passa pela limpeza de sempre (tags HTML, entidades, títulos `h2.`, negrito,
  cores, citações) e, agora, também sem imagem anexada (`!arquivo.png|width=1!`), sem link `[texto|https://...]` (fica só o
  texto) e sem itálico `_texto_` (identificadores como `PROCESSO_CARGA` não são mexidos).
- Ids de fonte: `campo`, `descricao`, `descricao-inicio`, `descricao-linhas`, `comentario-N` (posição do comentário),
  `subtarefa-CHAVE`, `pai-CHAVE`.
- `label` já vem pronto para a tela: "Comentário de Ana Ribeiro, 14/10", "Descrição, início do texto", "Issue pai · PTV-1290".
  A data é a escrita pelo Jira, sem converter fuso.

## 2. `origins` no card (tabela `showcase_task_origins`)

```ts
// ShowcaseTask -> campo NOVO
interface ShowcaseTask {
  /* ...campos de hoje, inalterados... */
  origins?: FieldOrigin[];  // na leitura: sempre presente ([] se o card não veio da importação)
}
interface FieldOrigin {
  field: 'problem' | 'solution' | 'acceptanceCriteria';
  sourceKind: 'campo' | 'comentario' | 'descricao' | 'subtarefa' | 'pai';
  sourceLabel: string;      // até 300 caracteres
  originalText: string;     // o que o Jira tinha; até 100.000 caracteres
  importedBy?: string;      // SÓ LEITURA: o servidor carimba com o token
  importedAt?: string;      // SÓ LEITURA
}
```

- **Escrita**: vai junto do card em `POST /api/showcase-sessions`. `origins` ausente (cliente que não conhece o campo) =
  as origens gravadas **não mudam**. `origins: []` apaga as do card. Uma origem por campo; repetida, vale a última.
- **Validação**: campo ou `sourceKind` fora da lista, ou `originalText` vazio, é `400` e **a Review inteira não é regravada**.
- **Limpeza**: card que sai da Review leva as origens junto. A tabela não tem FK para `showcase_tasks` (regravada por inteiro a
  cada save), como `showcase_task_files`.
- **Restaurar do Jira** não tem rota própria: o cliente grava o `originalText` no campo pelo `PATCH .../cards/{cardId}` de
  sempre (com `version`). A origem continua guardada.
- `PATCH` não altera `origins`. As origens também vão no card da resposta do `PATCH` e do `CARD_UPDATED`.
