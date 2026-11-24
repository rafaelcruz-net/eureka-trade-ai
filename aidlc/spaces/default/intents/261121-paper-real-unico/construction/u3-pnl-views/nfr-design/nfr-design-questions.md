# Perguntas do NFR Design — U3 `u3-pnl-views`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u3-pnl-views/nfr-requirements/` (NFR1.1 a NFR8.13 e D-NFR1/D-NFR2);
- `construction/u3-pnl-views/functional-design/`;
- `construction/u2-real-bot-pnl/nfr-design/`: leitura em fluxo, épocas, `RateLimitedAuditPublisher` e o pool de auditoria;
- `construction/u1-mode-skeleton/nfr-design/`: pool `pnl-mode-block` e prazo do bloco.

## Q1. Leitura das linhas do bot (C5) e da comparação (C6)

Como C5 e C6 leem os dados?

A. As duas reutilizam o cálculo em fluxo da U2 (`RealBotPnl` e o PAPER), sem cálculo novo:
   - **C5** pagina pelo cursor da chave de ordem de rede (slot, assinatura, desempate), que é estável. Uma ordem nova entra no fim e não desloca as páginas já lidas.
   - **C6** lê os dois lados em paralelo no pool `pnl-mode-block`, cada um com o seu prazo. Um lado que falha vira `UNAVAILABLE` sem derrubar o outro.

   Não há cache e não há tabela nova. (recomendado)
B. Cache das linhas por bot
X. Other (please specify)

[Answer]: A

## Q2. Auditoria assíncrona da leitura (NFR3.13, NFR3.14 e NFR3.17)

Como a auditoria das recusas é gravada sem atrasar a resposta?

A. O 404 de outro tenant e o 422 entregam o evento a um executor pequeno da auditoria, com fila limitada. A resposta sai sem esperar a gravação. O `RateLimitedAuditPublisher` da U2 aplica o limite de 1 por hora.

   Até a resposta, o 404 de outro tenant e o 404 de um uuid inexistente seguem o mesmo caminho de código. A única diferença é o envio ao executor, que não bloqueia.

   Se a fila estiver cheia, o evento é descartado, com um log `ERROR` e o contador `pnl_read_audit_failures_total`. A resposta não muda. (recomendado)
B. Gravar no mesmo pedido, com prazo
X. Other (please specify)

[Answer]: A

Nota do alinhamento A1 (decisão F-1, Code Generation da U3, 2026-11-23): a verificação `ownedByAnotherTenant` roda na thread do pedido, nos dois 404 (uuid inexistente e outro tenant), para que os dois caminhos façam o mesmo trabalho até a resposta; só o envio ao executor da auditoria fica depois, sem espera. A regra de paridade de tempo (NFR3.17) não muda e é medida pelo `BotNotFoundTimingParityIT`.

## Q3. Tela: releitura e pedidos

Como as telas evitam pedidos em excesso?

A. Assim:
   - **Releitura do cartão do painel:** um temporizador por cartão com janela de 30 s, que faz 1 pedido imediato e deixa no máximo 1 pendente.
   - **Filtro e período:** quando mudam, o pedido anterior é cancelado (`AbortController`) e sai um pedido por bloco.
   - **"Tentar de novo":** usa `market` e relê só o bloco que falhou.

   Os estados ficam no reducer que já existe na tela de P&L. Nenhuma biblioteca nova. (recomendado)
B. Biblioteca de cache de dados
X. Other (please specify)

[Answer]: A

Nota do alinhamento A4 (decisão F-3, Code Generation da U3, 2026-11-23): a resposta partiu de uma premissa errada, porque o `PnlScreen` não tinha reducer: usava vários `useState`. O fundador aceitou a reescrita maior: o estado de `/pnl` foi para `features/pnl/pnl-screen-state.ts`, um reducer puro com `useReducer` e nenhuma biblioteca nova. Os casos do `PnlScreen.test.tsx` da U1 que esperavam o formulário antigo e a tabela por token na abertura foram ajustados, um a um, ao comportamento novo ("Desde o início" por padrão, BR2.1 e BR2.9), sem apagar nenhum (conflito 2 do plano); o `PnlModeBlock.test.tsx` ficou sem edição.

## Q4. Observabilidade e componentes

O que muda em observabilidade e em componentes?

A. Três contadores novos, todos no módulo `pnl`, sem alarme novo e fora da lista do CloudWatch:
   - `pnl.compare.template_mismatches`;
   - `pnl.compare.side_unavailable{market,cause}`;
   - `pnl.read_audit_failures{action}`.

   Os logs saem em JSON com o `correlationId`. Não entra infraestrutura nova nem configuração por variável de ambiente. O tamanho do executor de auditoria fica numa constante, validada por teste. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Leitura:** C5 e C6 reutilizam o cálculo em fluxo da U2. C5 pagina por um cursor estável na ordem de rede. C6 lê os dois lados em paralelo, cada um com o seu prazo. Sem cache e sem tabela nova [Q1].
- **Auditoria:** gravada por um executor com fila limitada, sem atrasar a resposta. Os dois 404 seguem o mesmo caminho até a resposta. Com a fila cheia, o evento é descartado com log e contador [Q2].
- **Tela:** o cartão relê no máximo 2 vezes em 30 s. Trocar o filtro ou o período cancela o pedido anterior. O "Tentar de novo" relê um bloco só. Nenhuma biblioteca nova [Q3].
- **Observabilidade:** três contadores novos no `pnl`, sem alarme e sem infraestrutura nova [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
