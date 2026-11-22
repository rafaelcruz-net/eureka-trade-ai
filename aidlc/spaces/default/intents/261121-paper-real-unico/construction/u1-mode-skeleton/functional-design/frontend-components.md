# Componentes de interface — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 cria cinco componentes no `apps/web` (ModeStep, RealConfirmDialog, RealBotLimitsStep, SimulatedBadge e PnlModeBlock na variante `full`) e muda as telas de criação, do assistente, da lista `/bots` e do resultado `/pnl`. Este arquivo descreve a hierarquia, as props e o estado, a interação, a validação e a integração com a API. Os textos de interface ficam em português do Brasil; nomes de componentes, props e valores de enum ficam em inglês (`team.md`, Code Style).

## Hierarquia

```
/bots/new  (BotsShell > BotCreate)                     etapa 1 de 5: Modo e nome
  ModeStep                                             fieldset "Modo do bot"
    SimulatedBadge | RealBadge (existente)             selo de cada cartão
    motivo de indisponibilidade + link (WALLET_INACTIVE)
  "Estratégia modelo" e "Nome do bot" (existentes)
  RealConfirmDialog (sobre ModalDialog existente)      só com market = REAL

/bots/new/{id}  (BotsShell > BotWizard)                etapas 2 a 5
  linha "Modo: <selo> <modo> · não pode ser trocado"
  etapa 3: RealBotLimitsStep (REAL) | LimitFields existente (PAPER)

/bots  (BotsShell > BotList)
  aviso do simulado (quando há bot simulado visível)
  tabela (>= 768 px) | cartões (< 768 px)
    coluna Modo: SimulatedBadge | RealBadge
    coluna P&L: valor na moeda do modo, "—", "desconhecido" ou "P&L indisponível agora"

/pnl  (PnlScreen)
  h1 "Resultado"
  PnlModeBlock market=PAPER variant=full               h2 "Simulado, em dólares" + SimulatedBadge
  PnlModeBlock market=REAL  variant=full               h2 "Real, em SOL" + RealBadge
  ReconciliationPanel e LedgerList (existentes, PAPER, com SimulatedBadge)
```

## Componentes novos

### ModeStep

Lugar: `apps/web/src/features/bots`. Fonte: interaction-spec "ModeStep", mockups M1.

| Prop | Tipo lógico | Obrigatória | Padrão | Descrição |
|---|---|---|---|---|
| `value` | `'PAPER' \| 'REAL'` | sim | `'PAPER'` | modo escolhido (BR1.7) |
| `onChange` | função(market) | sim | — | chamada só com uma opção disponível |
| `eligibility` | `loading` \| `error` (com código) \| `ready` (`available`, `reason?`, `effectiveAt?`) | sim | — | resposta de C2 |
| `onRetry` | função | sim | — | relê C2 |
| `timeZone` | texto | não | fuso do navegador | para "Vale a partir de dd/mm HH:MM" |

`reason` usa os valores do contrato: `KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED`, `QUARANTINE`.

- **Estado interno:** nenhum além do anúncio da região viva; o valor e a elegibilidade vêm do pai (BotCreate).
- **Interação:** dois rádios nativos controlados num `fieldset` com o `legend` "Modo do bot". "Real" indisponível usa a marca de indisponível (não o bloqueio nativo), continua na ordem das setas, e a seta, o clique ou o Espaço sobre ele não mudam o valor; uma região viva educada repete "Real indisponível: <motivo>" (BR9.4).
- **Textos dos motivos:** os cinco de BR2.7; só `WALLET_INACTIVE` tem o link "Ativar a carteira do bot" para `/real`; a quarentena acrescenta "Vale a partir de dd/mm HH:MM" no fuso da pessoa (BR7.4).
- **Estados:** carregando ("Conferindo se o modo real está disponível…", ocupado), erro ("Não foi possível conferir o modo real agora.", código para o suporte, "Tentar de novo"), disponível, indisponível. "Simulado" fica disponível em todos (BR2.7).
- **Responsivo:** cartões empilhados abaixo de 768 px, lado a lado acima.

### RealConfirmDialog

Lugar: `apps/web/src/features/bots`, sobre o `ModalDialog` existente. Fonte: interaction-spec "RealConfirmDialog", mockups M2.

| Prop | Tipo lógico | Obrigatória | Descrição |
|---|---|---|---|
| `draft` | `{ name, templateName }` | sim | resumo mostrado |
| `cap` | lamports ou `Unknown` | não | teto da conta, só quando os limites já foram lidos na tela; sem a prop, a linha não aparece e o diálogo não faz pedido para ela |
| `onConfirm` | função(chave de idempotência) que devolve uma promessa | sim | cria o bot com `market = REAL` |
| `onClose` | função | sim | fecha sem efeito |
| `returnFocusTo` | elemento ou nulo | sim | o botão "Continuar para a confirmação" |

- **Estado interno:** `confirmed` (caixa), `phase` (`idle` \| `sending` \| `technicalError`), e a chave de idempotência criada quando o diálogo abre e reutilizada em toda repetição (BR1.4).
- **Validação:** "Criar bot real" fica desabilitado enquanto a caixa está desmarcada, com a frase sempre visível "Marque a confirmação para criar o bot real." como descrição do botão (BR1.5).
- **Envio:** o primeiro clique desabilita os dois botões e mostra "Criando o bot real…".
- **Resultados:** 201 fecha o diálogo e o pai abre o assistente; 409 `REAL_NOT_ELIGIBLE` fecha o diálogo e devolve o motivo ao pai (BR2.8); 5xx, tempo esgotado ou sem conexão mantêm o diálogo aberto com "Não foi possível criar o bot real. Tente de novo.", o código e "Tentar de novo" na mesma chave.
- **Acessibilidade:** diálogo modal nativo com papel de alerta; o foco começa em "Cancelar" e volta ao botão que abriu; Esc fecha, exceto durante o envio; clicar no fundo não fecha.

### RealBotLimitsStep

Lugar: `apps/web/src/features/bots`. Fonte: interaction-spec (M3b), mockups M3b.

| Prop | Tipo lógico | Obrigatória | Descrição |
|---|---|---|---|
| `load` | `loading` \| `error` (com código) \| `ready` (RealAccountLimits) | sim | resposta de C3 |
| `onRetry` | função | sim | relê C3 |
| `onBack` | função | sim | volta à etapa 2 |
| `onContinue` | função | sim | salva a etapa 3 sem saldo virtual nem limites (BR1.8) |

- **Exibição:** uma lista de termos com quatro linhas, cada valor lido com a unidade. SOL sai pelo módulo de dinheiro REAL, no formato de BR7.1.
  - "Teto da carteira do bot";
  - "Tamanho máximo por ordem", em SOL, com "(N% do teto)";
  - "Compras por bot, por dia UTC", como "até N";
  - "Perda máxima do dia", em SOL, com "(N% do teto)". O valor já vem calculado sobre a base `min(teto no início do dia, teto atual)`, a mesma da barreira.
- **"não definido":** um limite `NOT_DEFINED` aparece como "não definido". Sem teto, a ordem e a perda aparecem como "não definido (depende do teto)", e o link passa a "Definir o teto" (BR3.2). As compras por dia sempre têm valor.
- **Validação:** "Continuar" desabilitado ao carregar ("Espere os limites carregarem.") e depois de erro; disponível com valores ou "não definido" (BR3.4).
- **Sem campo editável.** O link "Alterar limites" leva a `/real`; ao voltar, a etapa relê.

### SimulatedBadge

Lugar: `apps/web/src/components` (comum). Fonte: interaction-spec "SimulatedBadge", C17.

| Prop | Tipo lógico | Obrigatória | Padrão |
|---|---|---|---|
| `testId` | texto | não | `'simulated-badge'` |

- Só exibição: ícone ◇ (oculto para o leitor de tela), contorno sem preenchimento e o texto `SIMULADO`. Não recebe foco e não carrega valor monetário.
- Substitui a etiqueta "MODO PAPER (simulado)" nos títulos de `/bots` e `/pnl`.

### PnlModeBlock (variante `full`)

Lugar: `apps/web/src/features/pnl`. Fonte: interaction-spec "PnlModeBlock", C17.

| Prop | Tipo lógico | Obrigatória | Padrão | Descrição |
|---|---|---|---|---|
| `market` | `'PAPER' \| 'REAL'` | sim | — | um modo só; não existe prop que receba os dois |
| `variant` | `'full' \| 'compact' \| 'bot'` | não | `'full'` | a U1 entrega `full`; a U3 acrescenta as outras |
| `load` | `loading` \| `error` (com código) \| `ready` (bloco do modo, do tipo gerado de C4) | sim | — | um bloco de C4; `UNAVAILABLE` vira `error` |
| `onRetry` | função | sim | — | relê C4 e atualiza só este bloco |
| `period` | texto do período | não | — | na U1, "desde o início" |
| `realUnavailable` | motivo de indisponibilidade do REAL | não | — | só no REAL vazio: escolhe o link ou o texto |
| `timeZone` | texto | não | fuso do navegador | para "marcação às HH:MM" |

- **Conteúdo `full`:** h2 com o selo; total com o rótulo de modo e moeda; realizado, não realizado e taxas; "Não realizado: posições abertas agora (marcação às HH:MM)"; lista dos bots do modo na ordem de criação, com o estado em texto e o total; o aviso fixo no PAPER (BR6.1). Na U1 não há linhas rateadas nem linha "Sem bot": `prorated` vem vazio e `unowned` vem `null` até a U2.
- **Taxas do REAL na U1 (BR5.5):**
  - a linha de taxas do bloco REAL e das linhas dos bots REAL tem o rótulo "Taxas (já incluídas no realizado e no custo)";
  - o total REAL é o realizado mais o não realizado, como chega da API;
  - no PAPER a linha continua "Taxas", subtraída no total;
  - na U2 o rótulo do REAL passa a ser igual ao do PAPER.
- **Nota do bloco REAL na U1:** na U1, o bloco REAL não vazio sempre mostra a nota "Algumas operações ainda não têm bot dono; o rateio chega na próxima etapa.".
  - O motivo: as ordens sem bot entram no total e em nenhuma linha, então a soma das linhas pode não fechar com o total.
  - A nota é fixa, porque a tela não soma as linhas para descobrir a diferença (BR5.4, BR7.5).
  - É um texto informativo, diferente do aviso do simulado e do "Dado parcial".
  - A U2 retira a nota quando a linha "Sem bot" e o rateio chegam.
- **Não soma nada:** os totais chegam prontos (BR5.4, BR5.5); o componente só formata (BR7.1, BR7.2, BR7.5).
- **Estados:** carregando (título e selo visíveis, esqueleto, "Calculando o resultado simulado…" ou "…real…", ocupado, nunca zero); erro ("Não foi possível carregar o resultado simulado/real.", código, "Tentar de novo"); vazio (PAPER: "Nenhuma operação simulada neste período." e "→ Criar bot simulado"; REAL: "Sem bots reais." e o link conforme o motivo); desconhecido (total "desconhecido (depende do não realizado)", aviso "◐ Dado parcial: há posição sem preço confiável.", diferente do aviso do simulado).
- **Acessibilidade:** `section` rotulada pelo h2; partes numa lista de termos; total numa região viva educada e atômica, anunciada só depois da carga inicial e no máximo uma vez a cada 30 s; depois de "Tentar de novo", o foco vai para o h2 do bloco (BR9.2).
- **Responsivo:** grade de duas colunas a partir de 768 px; tabela de bots a partir de 1.200 px; cartão de largura toda abaixo de 768 px (BR9.3).

## Telas que mudam

| Tela | Mudança na U1 | Regras |
|---|---|---|
| `BotCreate` (`/bots/new`) | indicador "Etapa 1 de 5: Modo e nome"; ModeStep antes da estratégia; leitura de C2 ao abrir; envio de `market`; botão "Criar rascunho simulado" ou "Continuar para a confirmação"; RealConfirmDialog no REAL; mensagem de recusa de domínio com o motivo | BR1.1, BR1.5, BR1.7, BR2.3, BR2.7, BR2.8 |
| `BotWizard` (`/bots/new/{id}`) | "Etapa N de 5"; linha do modo só em texto; etapa 3 do REAL com RealBotLimitsStep; "Iniciar bot simulado" ou "Iniciar bot real" | BR1.6, BR1.8 |
| `BotsShell` | h1 sem a etiqueta "MODO PAPER (simulado)"; na lista, h1 "Meus bots" | BR4.2 |
| `BotList` (`/bots`) | coluna Modo com o selo e o texto; P&L na moeda do modo a partir de C4; aviso do simulado; estados distintos; tabela e cartões | BR4.2 a BR4.4, BR6.1, BR9.3 |
| `PnlScreen` (`/pnl`) | h1 "Resultado"; dois PnlModeBlock `full`; foco inicial no h2 do bloco Simulado; conferência e ledger PAPER embaixo, com o selo | BR5.1 a BR5.3, BR9.2 |

## Validação de formulário

| Campo | Regra | Mensagem | Fonte |
|---|---|---|---|
| Modo | só `PAPER` ou `REAL`; `REAL` só com a elegibilidade disponível | motivo de BR2.7 | BR1.1, BR2.7 |
| Confirmação do REAL | caixa marcada antes de "Criar bot real" | "Marque a confirmação para criar o bot real." | BR1.5 |
| Nome do bot | 1 a 60 caracteres, aparado (existente) | "Informe um nome de 1 a 60 caracteres." | código existente |
| Etapa 3 REAL | sem campo; "Continuar" só com os limites lidos | "Espere os limites carregarem." | BR3.4 |

## Integração com a API

Os tipos vêm do contrato gerado de `openapi-guest.yaml`, nunca escritos à mão (`team.md`).

| Componente ou tela | Chamada | Respostas tratadas |
|---|---|---|
| BotCreate / ModeStep | `GET /api/v1/bots/real-eligibility` (C2) | 200; 503 (erro do cartão Real); 403 termos (fluxo existente) |
| BotCreate / RealConfirmDialog | `POST /api/v1/bots` com `market` e `Idempotency-Key` (C1) | 201; 409 `REAL_NOT_ELIGIBLE` com `reason`; 409 `IDEMPOTENCY_CONFLICT`; 422; 503 |
| BotWizard / RealBotLimitsStep | `GET /api/v1/real/account-limits` (C3) | 200; 409 `WALLET_INACTIVE`; 503 |
| BotList | `GET /api/v1/bots` (existente) e `GET /api/v1/pnl/by-mode` (C4) | lista e blocos independentes; bloco `UNAVAILABLE` vira "P&L indisponível agora" |
| PnlScreen / PnlModeBlock | `GET /api/v1/pnl/by-mode` (C4) | 200 com cada bloco disponível ou `UNAVAILABLE`; 400 só por parâmetro inválido |

## Alargamento do modo no leitor do painel (`envelopes.ts`)

Obrigação de C1 (revisão R-01) e de BR10.5. Hoje o leitor do resumo do bot em `apps/web/src/features/dashboard/envelopes.ts` aceita só `PAPER` no campo `mode` e recusa o envelope inteiro com `REAL`. No mesmo commit da mudança da API e do contrato:

1. o leitor aceita `PAPER` e `REAL` em `mode`, ou deixa de ler `mode` e lê só `market` (ADR-009); a recomendação é ler só `market`, que já é lido e validado;
2. as fixtures e os testes do `apps/web` que fixam `mode: 'PAPER'` (em `features/bots`, `features/pnl` e `features/dashboard`) são atualizados;
3. um teste de contrato do `apps/web` confere que o resumo de um bot REAL (`market: REAL`, `mode: REAL`) é aceito pelo leitor do painel e pelos tipos gerados;
4. o teste de contrato do backend de `mode == market` continua.

Também no mesmo conjunto de mudanças da U1, a proibição de lint do dinheiro, que hoje vale para `features/real` e `app/real`, passa a valer para `features/pnl` (BR7.5).

## Sources

- `inception/refined-mockups/interaction-spec.md`: ModeStep, RealConfirmDialog, SimulatedBadge, PnlModeBlock, "Componentes que já existem e mudam".
- `inception/refined-mockups/mockups.md`: M1 a M5, D1 e D2; `accessibility-checklist.md`: `/bots/new`, `/bots/new/{id}`, `/bots`, `/pnl`.
- `inception/contract-design/contract-summary.md`: C1 (obrigações do alargamento de `mode`), C2, C3, C4, C17.
- `inception/domain-design/components.md`: ModeStep, RealConfirmDialog, RealBotLimitsStep, SimulatedBadge, PnlModeBlock; ADR-010.
- `functional-design-questions.md`: Q4, Q5, Q6.
- `rules.md` e `entities.md` desta pasta.
- Código lido: `apps/web/src/features/dashboard/envelopes.ts` (`oneOf(raw.mode, ['PAPER'])`), `features/bots/BotCreate.tsx` (não envia `market` hoje), `features/real/money.ts`, `features/pnl/decimal.ts`, `features/real/components` (`ModalDialog`, `RealBadge`, `RealErrorNotice`).

## Assumptions & Open Questions

1. [assumption] O `RealErrorNotice` passa a ser usado também nos erros do bloco PAPER e da lista, para mostrar o código para o suporte; ele muda para `components/` ou ganha um equivalente comum, conforme a regra de lint de `features/real` (OQ5 dos mockups).
2. [assumption] O formatador de SOL de BR7.1 é uma função nova do módulo de dinheiro REAL, ao lado do `formatSignedSol` de 9 casas, que continua nas telas REAL existentes (BR7.3).
3. [assumption] O nome do componente novo é `ModeStep`, distinto do `ModeSelector` que já existe em `features/real/components` (modo de operação REAL), para não confundir os dois.
