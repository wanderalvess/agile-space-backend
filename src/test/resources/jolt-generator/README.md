# Casos do gerador de spec Jolt

Cada pasta é um caso real: `entrada.json` (resposta da API de origem), `saida.json` (o que a API de destino espera) e
`spec.json` (o `LAYOUTTRANSFORMACAO` em produção). O `spec.json` rodado no motor Java (Bazaarvoice) com a
`entrada.json` gera exatamente a `saida.json` (conferido em 2026-10-14, zero diferença).

O teste do gerador NÃO compara o spec gerado com `spec.json` (pode ser diferente e menor): roda o spec gerado na
`entrada.json` e compara a saída com `saida.json`.

| Caso | Rota | O que exercita |
|---|---|---|
| `clientes` | WTA - Buscar Clientes | lista simples; só dígitos (CPF, CEP, telefone); concatenação (`idExterno`); de-para com 1 valor de exemplo (`corporate`, `wholesalePriceUses`); 3 grupos de endereço com o mesmo valor (entrega, cobrança, comercial); fixos e variáveis da rota |
| `produtos` | PDVSync - Produtos | objeto aninhado (`produto`); campos dentro de `{valor}`; valor tirado de outra lista (`icms[0].cst`); listas alinhadas item a item (`embalagens[]`, `piscofins[]`, `ncm[]`, `icms[].fcp`); concatenação com campo do pai (`descricao` da embalagem); muitos valores 0/false/"UN"/"1" repetidos (o valor sozinho não decide a origem) |

## O que um exemplo só não mostra (precisa de outro caso ou de regra editada)

- `produtos`: `impostos.idRetaguarda` é `<id>-ST` só porque `icmsExterno.valor` ≠ 0 (senão `<id>-ICMS`); ICMS e PIS/COFINS
  com código 0 são descartados; `tipoMercadoria` tem 7 valores no de-para (o exemplo só traz LIBERADO); `situacao`
  "Inativo" e `inativo` true; FCP troca ponto por vírgula (com valor 0 não aparece); kits e similares vazios.
- `clientes`: `corporate: true`, `wholesalePriceUses: "S"`, `sexo` (não vem na entrada) e campos vazios que caem nos padrões.

O spec de `produtos` tem partes que não afetam a saída (`ncm_old`, `codigo_Ncm`, `produto.codigoProduto`,
`produto.codigoFilial` no primeiro passo).
