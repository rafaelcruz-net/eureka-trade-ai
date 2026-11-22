# Requisitos de confiabilidade — U1 `u1-mode-skeleton`

Reúne o comportamento sob falha (Q4), a exatidão dos valores (NFR2), os estados da interface (NFR5, NFR6) e a verificação (NFR7, Q2). Este estágio não tem arquivo próprio para acessibilidade e testes; eles ficam aqui porque são parte de "a U1 faz o que promete" [Q2] [Q4].

## Disponibilidade

| ID | Requisito | Valor |
|---|---|---|
| NFR4.7 | A U1 não cria meta de disponibilidade nova além da do monólito. | 0 SLOs novos; o grupo de prontidão (`readinessState`, `db`, `migrations`) não muda; nenhuma rota da U1 entra na verificação de saúde. |

## Tolerância a falhas e degradação

| ID | Requisito | Valor |
|---|---|---|
| NFR4.4 | Quando um modo falha em C4, o outro bloco continua; o bloco que falhou vem `status: UNAVAILABLE` com `code` e `correlationId` (BR5.3). | Resposta 200 em 100% dos casos de falha de um só modo, e também quando os dois falham (os dois `UNAVAILABLE`); 0 respostas 500 inteiras por causa de um modo. Teste de integração com falha injetada em cada modo, um de cada vez e os dois juntos (3 cenários). |
| NFR4.5 | Se a fonte da elegibilidade falha antes de decidir um motivo, a criação REAL e a leitura C2 respondem 503 `SERVICE_UNAVAILABLE` com falha fechada; a criação PAPER segue sem ler a elegibilidade (BR2.5). | Teste de integração: com a leitura do estado REAL falhando, `POST /api/v1/bots` com `market = REAL` responde 503 e cria 0 bots; `market = PAPER` responde 201 e cria 1 bot; C2 responde 503. Se um motivo já foi decidido antes da leitura que falhou, vale o motivo (409). C3 responde 503 quando `RealAccountLimits` lança `RealLimitsUnavailable`. |
| NFR4.8 | As leituras C2, C3 e C4 são idempotentes e podem ser repetidas sem efeito colateral. | 0 gravações no banco por leitura (teste de integração conta as linhas das tabelas tocadas antes e depois de 3 leituras seguidas). O "Tentar de novo" de um bloco relê C4 inteira. |
| NFR4.9 | A criação (C1) usa a `Idempotency-Key` obrigatória (uuid) e o mecanismo comum de idempotência do módulo de identidade (BR1.4). | Mesma chave e mesmo corpo: a mesma resposta e 1 bot só. Uma recusa 409 ou um 503 não fica guardado, e a repetição com a mesma chave reavalia a elegibilidade. Sem o cabeçalho: 400. |

## Exatidão

| ID | Requisito | Valor |
|---|---|---|
| NFR2.1 | Valores em decimal exato ou em unidades base, nunca em ponto flutuante (BR5.8, BR7.5). | REAL em lamports `long` com operações exatas e em texto inteiro no JSON; PAPER em `BigDecimal`; no `apps/web`, `bigint` só em `features/real/money.ts` e o lint do dinheiro em `features/pnl`; a regra ArchUnit de ponto flutuante continua verde. |
| NFR2.2 | O P&L PAPER por bot de C4 é igual ao de hoje e fecha com o ledger por igualdade exata (BR5.9, BR5.10). | Teste de integração com cenário de resultado conhecido: diferença de 0 entre o total PAPER de C4 e a conferência do ledger, comparada com `compareTo == 0`. |
| NFR2.3 | A linha REAL da U1 vem das ordens do bot, com o total `realized + unrealized`, e o dado ausente vira `UNKNOWN`, nunca zero (BR5.4, BR5.5). | Cenários determinísticos de resultado conhecido em lamports, escritos antes do código (núcleo financeiro); com preço ausente, `unrealized.status = UNKNOWN` e o total do bloco `UNKNOWN`. |

## Uso e interface

| ID | Requisito | Valor |
|---|---|---|
| NFR5.1 | Navegação por teclado e foco visível na escolha do modo, na etapa de limites e na tela de P&L (BR9.2, BR9.4). | 100% dos controles da U1 alcançáveis por Tab e acionáveis por Enter ou Espaço; teste de componente com Testing Library por controle. |
| NFR5.2 | Contraste mínimo de 4,5:1 no tema escuro, e cor sempre acompanhada de texto (BR9.1). | Os tokens de cor usados pelos componentes novos têm contraste ≥ 4,5:1 sobre o fundo; 0 estados indicados só por cor. |
| NFR5.3 | Cada total tem rótulo para leitor de tela com o modo e a moeda; a tela tem um `h1` e um `h2` por modo (BR9.2). | Teste de componente confere o nome acessível de cada total (por exemplo, "Total do Simulado em US$") e a hierarquia de títulos. |
| NFR6.1 | A tela funciona a partir de 360 px de largura, com cartões empilhados e sem rolagem lateral (BR9.3). | No spec de ponta a ponta, na largura de 360 px, `scrollWidth ≤ clientWidth` na tela de P&L e na de Bots. |
| NFR6.2 | Carregando, erro (bloco `UNAVAILABLE` ou falha da rota), desconhecido (`unknown`) e vazio são estados visíveis distintos entre si e do estado pronto (BR4.4, BR9.3). | Teste de componente para cada um dos 5 estados da máquina de `PnlModeBlock` (Carregando, Pronto, Vazio, Desconhecido, Erro), com texto distinto em cada um; "Tentar de novo" no Erro relê só aquele bloco. |

## Verificação

| ID | Requisito | Valor |
|---|---|---|
| NFR7.1 | Volume de testes da U1: a Test Strategy `Standard` registrada no estado do intent; o `Comprehensive` do NFR7 continua como volume do projeto [Q2]. | Testes de unidade e de integração de cada regra BR1.1 a BR10.5 com caminho feliz e pelo menos 2 casos de erro ou borda por componente. |
| NFR7.2 | Testes obrigatórios por risco que a U1 toca, sempre verdes. | (1) isolamento entre contas e 404 (NFR3.1, NFR3.2); (2) separação REAL e PAPER, com os modos nunca somados (NFR4.1, NFR4.2); (3) REAL desligado por padrão (NFR3.9); (4) novo aceite dos termos nas 4 rotas (NFR3.3); (5) idempotência da criação (NFR4.9, NFR1.12); (6) dado ausente vira `unknown` (NFR2.3); (7) P&L com cenários determinísticos (NFR2.2, NFR2.3). |
| NFR7.3 | Piso de cobertura de 80% de linhas, medido em cada parte. | Backend: JaCoCo, unitários e integração mesclados, ≥ 80%; `apps/web`: Jest, `coverageThreshold.global.lines = 80`; `apps/admin` e `apps/txsigner` não mudam e continuam ≥ 80%. A ferramenta falha o build abaixo do piso; o piso nunca é reduzido. |
| NFR7.4 | Teste do percurso de BR1.9: um bot REAL não tem limites próprios. | 1 teste de integração com 6 passos (validar, ativar, rodar um ciclo, pausar, retomar, parar) para um bot REAL sem saldo virtual nem limites próprios, 0 exceções e 0 versões de limites do bot gravadas; e o mesmo percurso para um bot PAPER, com o comportamento de hoje. |
| NFR7.5 | Testes de BR2.4 e BR2.1: recusa REAL com 409 `REAL_NOT_ELIGIBLE`. | 5 testes de integração (um por motivo: `KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED`, `QUARANTINE`) com 409, `reason` correto, 1 registro de auditoria e 0 bots; 1 teste de regressão para `WALLET_INACTIVE`, que antes respondia 422; testes de unidade da função pura de BR2.1 para cada um dos 5 motivos e para cada um dos 10 pares de motivos. |
| NFR7.6 | Ordem dos testes: antes do código no núcleo financeiro e na idempotência, depois do código no restante. | Antes do código: P&L por modo (NFR2.2, NFR2.3), deduplicação da criação (NFR4.9). Depois de cada camada: telas, rotas de leitura, configuração. |
| NFR7.7 | Spec de ponta a ponta da fatia do intent (BR10.1), acrescentado ao `scripts/verify.sh` pelo responsável. | O spec cria um bot PAPER e um bot REAL no validador Solana local, faz uma compra em cada modo e confere que cada bloco mostra só o próprio modo e que nenhum total soma os modos; `bash scripts/verify.sh` devolve 0 nos jobs `smoke` e `e2e`. |
| NFR7.8 | O alargamento de `mode` para REAL entra no mesmo commit que atualiza os leitores do `apps/web` (BR10.5). | No mesmo commit: o teste de contrato do `apps/web` aceita o resumo de um bot REAL e o teste de contrato do backend de `mode == market` continua verde; os jobs `frontend` e `contract` ficam verdes. |

## Sources

- `nfr-requirements-questions.md`: Q2, Q4 e resumo confirmado [Q2] [Q4].
- `inception/requirements-analysis/requirements.md`: NFR2, NFR4, NFR5, NFR6, NFR7.
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR1.4, BR1.9, BR2.1, BR2.4, BR2.5, BR4.4, BR5.3 a BR5.5, BR5.8 a BR5.10, BR7.5, BR9.1 a BR9.4, BR10.1, BR10.5; suposição 3 (recusa e 503 não ficam guardados).
- `construction/u1-mode-skeleton/functional-design/frontend-components.md`: `PnlModeBlock`, `ModeStep`, `RealBotLimitsStep`.
- `inception/contract-design/contract-summary.md`: C1 (repetição segura), C3 e C9 (`RealLimitsUnavailable`), C4 (estado parcial, repetição).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Ordering, Volume, Piso, testes obrigatórios por risco), Code Style (valores do REAL em inteiros, `unknown`).
- Código lido: `application.yml` (grupo de prontidão).

## Assumptions & Open Questions

1. [assumption] O teste de contraste do NFR5.2 é feito sobre os tokens de cor (sem portão automático de acessibilidade na CI, como diz o `team.md`); a conferência visual fica nos gates de aprovação.
2. [assumption] O NFR4.8 diz que o "Tentar de novo" relê C4 inteira, porque C4 é uma só rota; a tela atualiza só o bloco que estava em Erro (PQ-6 do contrato).
