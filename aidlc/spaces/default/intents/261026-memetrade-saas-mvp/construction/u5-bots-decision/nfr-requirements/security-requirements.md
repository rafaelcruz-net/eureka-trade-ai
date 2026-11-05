# Requisitos de segurança e integridade — U5 Bots e decisão

Requisitos de segurança e integridade da Unit `u5-bots-decision`. As fontes são:
- as regras de [rules.md](../functional-design/rules.md);
- o NFR4 a NFR8 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- os contratos C4, C7, C14, C15 e C16 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- o `project.md` e o `team.md`.

A decisão sobre limite de requisições vem da Q4 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Classificação dos dados

| Dado | Classe | Proteção |
|---|---|---|
| Bots, limites, decisões, ciclos e aberturas de posição | Pessoal e financeiro (simulado) | Só a pessoa dona lê, por tenant e pessoa do token; tarefas usam o Principal do dono |
| Estado do kill switch na U5 (`SystemPauseState`) | Interno | Escrito só por `BotAdministration`; sem rota do convidado |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR4.1 | Decisões e ciclo de vida exatos | Catálogos D1 a D18 e L1 a L14 escritos antes do código, por igualdade exata; teste de propriedade com seed fixa: nenhuma decisão BUY ou SELL sai sem sinal e sem a regra que a explica | NFR4, BR4 |
| NFR4.2 | Comando único por chave | Cada comando com a mesma `Idempotency-Key` dá uma transição e um evento; duplo clique em start e pause (50 repetições em paralelo) dá um só (teste obrigatório de deduplicação) | AC4.3.3, AC4.8.3 |
| NFR4.3 | Nunca mais de 3 ativos | Starts, resumes e meia-noite em paralelo para a mesma pessoa (50 repetições) nunca deixam 4 ativos | AC4.3.2, BR2.11 |
| NFR4.4 | Pausa impede operação | Pausa, kill switch e motivos de limite gravados antes da trava do ciclo: nenhuma chamada à U6 nem à U7, inclusive saídas (teste obrigatório de pausa) | FR11.1, AC4.8.1, AC4.8.2, AC7.2.2 |
| NFR4.6 | Dado ausente vira NO_TRADE, nunca zero | Com C11 ou C12 respondendo `UNKNOWN`, velho ou indisponível em cada campo usado (preço, volume, liquidez, avaliação, filtro), a decisão é NO_TRADE com o motivo ou o token fica fora do filtro. Nenhuma conta usa zero, e a decisão grava o valor como `UNKNOWN` em `inputs` (teste obrigatório de `unknown`; D3 e casos por campo) | FR8.2, AC4.5.3, BR4.1, BR4.9 |
| NFR4.7 | Uma ordem por fatia | `BotCycle` único por (botId, slotStart) e chave `order:<decisionId>` na execução: dois disparos da mesma fatia em paralelo, ou depois de reinício (50 repetições), geram uma decisão e no máximo uma ordem; a segunda chamada à U7 com a mesma chave devolve `Duplicate` (teste obrigatório de deduplicação) | BR3.2, BR3.4, AC4.6.3 |
| NFR4.5 | Sem ponto flutuante | ArchUnit: nenhum `double` ou `float` nos pacotes da U5; `numeric` no banco; `DecimalString` no JSON e no frontend (lint) | `project.md` |
| NFR5.1 | Só a dona vê os bots | Todas as rotas C4 e o canal C7 com bot de outra pessoa e de outro tenant: 404, nunca o dado | DP-1, NFR5 |
| NFR5.2 | Tarefas com o dono | Ciclo, meia-noite, conferência do encerramento e expurgo ligam o Principal do dono lido da linha; teste com dois tenants confere que nada se mistura | BR2.10 |
| NFR5.3 | Só PAPER | `CHECK mode = 'PAPER'`; `mode` diferente na requisição dá 403 auditado; nenhuma rota transfere saldo ou muda modo (teste obrigatório de REAL e PAPER) | AC4.2.5, AC4.3.5 |
| NFR6.1 | REAL desligado | ArchUnit: os pacotes da U5 não dependem de cliente HTTP externo, SDK Solana, assinatura nem roteador de swap; contador do componente de assinatura igual a zero com bots operando (teste obrigatório de REAL desligado) | AC4.3.4, FR15.2 |
| NFR6.2 | Entrada validada | Parâmetros, seleção, saldo e limites validados no servidor como no cliente; parâmetro desconhecido recusado; `ProblemDetail` sem SQL nem pilha | BR1.2 a BR1.5 |
| NFR6.3 | Sem limite de requisições próprio | Os comandos não têm limite por pessoa (Q4); a idempotência e o máximo de 3 ativos contêm o abuso | Q4 |
| NFR6.4 | Sem IA no beta | ArchUnit e teste de contexto: nenhum componente de modelo de IA carregado | FR8.6, AC4.5.5 |
| NFR7.1 | Nada sensível em logs | Logs só com identificadores (`botId`, `cycleId`, `decisionId`); `LogScanIT` cobre um ciclo e os comandos | NFR7 |
| NFR8.1 | Decisão reproduzível | Para cada decisão do catálogo, recalcular só com `inputs`, `context`, parâmetros, versão e limites gravados dá a mesma decisão | NFR8, AC4.5.2 |

## Ameaças (STRIDE)

| Categoria | Ameaça | Controle |
|---|---|---|
| Falsificação | Comandar o bot de outra pessoa pelo `botId` | 404 por dono (NFR5.1) |
| Adulteração | Contornar a barreira | O ciclo só chama a execução com `Approved` da U6 (ArchUnit e teste) |
| Adulteração | Ativar o quarto bot por corrida | Trava de `BotOwner` (NFR4.3) |
| Repúdio | Negar uma pausa ou retomada | Evento por comando, com ator e motivo |
| Divulgação | Canal ao vivo com dados de outra pessoa | Filtro por token em cada leitura (NFR5.1) |
| Elevação | Operar em REAL | Só PAPER; nenhum código assina (NFR5.3, NFR6.1) |
