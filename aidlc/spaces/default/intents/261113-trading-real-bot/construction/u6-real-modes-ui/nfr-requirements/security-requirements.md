# Requisitos de segurança e isolamento — U6 `u6-real-modes-ui`

Conversa: português (Brasil). Fontes:
- `nfr-requirements-questions.md` (Q2, Q3, Q7);
- `construction/u6-real-modes-ui/functional-design/rules.md` (BR3, BR5, BR6, BR8, BR9 e o adendo);
- `project.md` (Forbidden e Mandated).

**Pastas do REAL:**
- `apps/web/src/app/real/`;
- `apps/web/src/features/real/`;
- `apps/admin/src/app/real/`;
- `apps/admin/src/features/real/`.

As regras de lint abaixo valem nessas pastas, num bloco do ESLint (config flat) que repete as restrições que já existem no app, como a proibição de `EventSource`, porque um bloco mais específico substitui as opções do anterior.

## Navegador e transação

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR1.1 | A transação de `transactionBase64` é passada a `signTransaction` sem alteração, e o navegador devolve a transação que a carteira assinou. | teste que compara byte a byte a mensagem dada à carteira de teste com a recebida |
| U6-NFR1.1b | O navegador não envia transação à rede. Nos dois apps não há import de biblioteca de transação Solana (`@solana/web3.js`, `@solana/kit`, `@solana/spl-token`, `@coral-xyz/anchor`), nem literal `sendTransaction` ou `sendRawTransaction`. | regra ESLint `no-restricted-imports` e `no-restricted-syntax`, com fixture que falha; teste de dependências (lista permitida) |
| U6-NFR1.2 | Nada do REAL vai para `localStorage`, `sessionStorage`, `document.cookie`, `console.*` ou parâmetros de URL: transação, assinatura, endereço completo, valor ou chave de idempotência | regra ESLint `no-restricted-properties` e `no-restricted-globals` nas pastas do REAL, com fixture que falha; teste que confere o armazenamento vazio depois dos fluxos |
| U6-NFR1.3 | Nenhum SDK Solana nem biblioteca de transação no código de produção dos dois apps; só o `@solana/wallet-standard-features`, que já existe | teste de dependências (lista permitida) no `apps/web` e no `apps/admin` |
| U6-NFR1.4 | A CSP com nonce que já existe continua valendo, sem script de terceiros novo | os testes do `middleware`, que já existem, continuam verdes |
| U6-NFR1.5 | Nenhuma variável `NEXT_PUBLIC_*` nova; a rede vem de `GET /api/v1/real/release` | teste que lista as variáveis `NEXT_PUBLIC_*` contra a lista atual |
| U6-NFR1.9 | Texto que vem do servidor (motivo do bot, `detail` do ProblemDetail, motivo do administrador, nome do token) é sempre texto, nunca HTML | regra ESLint `react/no-danger` como erro nas pastas do REAL, com fixture que falha |

## Servidor (adendo)

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR1.6 | A rota `GET /api/v1/real/release` lê o tenant do contexto de autenticação, entra no registro de rotas e na regra de tenant, e devolve `403 REAL_DISABLED` com o REAL desligado | testes de rota e de isolamento (outra conta nunca vê a liberação de ninguém) |
| U6-NFR1.7 | O CORS do convidado aceita `PATCH` só no ramo com token. As rotas por cookie e o CORS do administrador continuam sem `PATCH`. | teste do `GuestCorsPolicy` com as duas metades: `PATCH` com token aceito; `PATCH` no ramo por cookie recusado; CORS do administrador sem mudança |

## App do administrador

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR1.8 | A carteira principal aparece abreviada; nenhuma tela tem campo de chave | teste de componente |
| U6-NFR1.10 | O token de acesso do administrador fica só em memória, como hoje; nada da seção REAL vai para armazenamento do navegador | U6-NFR1.2 vale também em `apps/admin`; os testes de sessão que já existem continuam verdes |
| U6-NFR1.11 | Toda ação do administrador pede um motivo de 1 a 500 caracteres, contado em caracteres e conferido antes de enviar, e uma confirmação | teste de componente com 0, 1, 500 e 501 caracteres |

## Separação e dinheiro

| ID | Requisito | Verificação |
|---|---|---|
| U6-NFR2.1 | REAL e PAPER aparecem separados: cartões e valores nunca somados, e o selo `REAL` em todo valor do REAL | teste de componente do painel e da visão geral |
| U6-NFR7.1 | Todo valor em lamports ou em unidades de token é `bigint` desde a resposta da API, convertido por uma função única (`toBaseUnits`) | testes do cliente da API, com valores acima de 2^53 |
| U6-NFR7.2 | Um analisador decimal e um formatador próprios fazem toda conversão entre texto e valor. Eles são testados nos casos de borda: 0, 9 casas, 10 casas, vírgula e ponto, espaços, sinal, o máximo e valores acima de 2^53. O formatador mostra 9 casas para SOL e as casas do mint para token, no padrão pt-BR. | testes de unidade |
| U6-NFR7.3 | Nas pastas do REAL, ficam proibidos `Number(`, `parseFloat`, `parseInt`, `.toFixed(`, `Math.round`, `Math.floor`, `Math.ceil` e o `+` unário. A aritmética de dinheiro fica no analisador e no formatador, que operam só sobre `bigint`. A aritmética de tempo da contagem (U6-NFR3.2) e do acompanhamento (U6-NFR3.4) fica fora das pastas do REAL, em `apps/web/src/lib/time/countdown.ts`, com testes próprios, e não lida com dinheiro. | regra ESLint `no-restricted-syntax` e `no-restricted-globals`, com fixture que falha; TypeScript `strict` impede somar `bigint` com `number` |
| U6-NFR7.4 | A `Idempotency-Key` vale para um corpo exato, segundo BR6.2 | teste de componente: reenvio do mesmo corpo usa a mesma chave; corpo novo usa chave nova |
| U6-NFR7.5 | Um dado de mercado ausente aparece como "sem preço" ou "sem dado", nunca como zero; uma posição sem preço mostra o custo de compra com o rótulo | teste de componente com `unknown` em cada campo de mercado mostrado (BR8.2) |
| U6-NFR7.6 | O botão fica desabilitado enquanto o envio está em andamento; um segundo clique não gera outro pedido | teste de componente que conta os pedidos (BR6.3) |
