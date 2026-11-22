# Especificação de interação — paper-real-unico

Uma especificação por componente novo ou alterado, no formato de `.claude/knowledge/aidlc-design-agent/component-spec-template.md`. Cada uma cobre o comportamento, o teclado, o `aria` e os estados. As telas estão em `mockups.md` (M1 a M7).

As faixas de tela são as do [Q6]:
- celular: 360 a 767 px;
- tablet: 768 a 1.199 px;
- computador: 1.200 px ou mais.

Elas trocam as faixas genéricas do modelo.

## Sources

- `refined-mockups-questions.md`: Q1 a Q7, todas A, com o resumo confirmado.
- `inception/user-stories/stories.md`: US1.1 a US8.1 e os critérios citados em cada componente.
- `inception/requirements-analysis/requirements.md`: FR1 a FR8, NFR5 e NFR6.
- `ideation/rough-mockups/wireframes.md` e `user-flow.md`.
- O código atual: `ModalDialog`, `ModeSelector`, `RealBadge`, `RealErrorNotice`, `MoneyConfirmDialog` (`features/real/components/`); `BotCreate`, `BotWizard`, `BotList`, `BotDetail` (`features/bots/`); `PnlScreen`, `period.ts`, `SignedValue` (`features/pnl/`); `PnlCard`, `CardError` (`features/dashboard/`).

## Regras comuns a todos os componentes

- **Unidade e cálculo.**
  - Os valores chegam da API nas unidades base (lamports) ou em texto decimal (US$).
  - Os componentes só formatam: o SOL pelo `money.ts`, o US$ pelas funções decimais de `features/pnl/decimal.ts`.
  - Nenhum componente soma, subtrai ou converte valores entre modos.
  - Nenhum usa `number` de JavaScript para dinheiro (project.md: nada de ponto flutuante).
- **`unknown`.** Um valor ausente é o tipo `Unknown` (`money.ts`) ou `PnlAmount.status = 'UNKNOWN'`. A tela mostra "desconhecido" com o motivo, nunca zero.
- **Erro.** Usa o padrão do `RealErrorNotice`: `role="alert"`, o texto, "Código para o suporte: <correlationId>" e "Tentar de novo" [Q3].
- **Movimento.** Nenhum componente anima mais que 200 ms. Com `prefers-reduced-motion`, não há animação.
- **Toque.** O alvo de toque tem no mínimo 44 × 44 px abaixo de 768 px.
- **Foco.** Visível em todos os componentes, pelo `:focus-visible` que já existe (3 px na cor `focus`).

---

## ModeStep

| Field | Value |
|---|---|
| Component | ModeStep |
| Description | Escolha do modo do bot, "Simulado" ou "Real", na etapa 1 do assistente, com o motivo quando "Real" está indisponível (US1.1, US1.2) |
| Category | input |

### Comportamento

- **Padrão.** "Simulado" vem marcado (AC1.1.1).
- **Elegibilidade.** O componente lê a elegibilidade do REAL uma vez, quando a tela abre. A resposta é a disponibilidade e o motivo mais forte.
- **Precedência dos motivos** [Q7]:
  1. kill switch;
  2. REAL desligado;
  3. carteira do bot inativa;
  4. liberação ausente;
  5. liberação em quarentena.

  A tela recebe o motivo e mostra o texto dele.
- **Onde a precedência é resolvida.** A proposta é que o servidor já devolva só o motivo mais forte. Se ele devolver a lista, o componente aplica a mesma ordem. O teste de unidade confere a ordem nos dois casos.
- **"Real" indisponível.** A escolha não muda e o motivo continua visível. "Simulado" continua marcado (AC1.2.9).
- **Link de resolução.** Só a carteira inativa tem link: "Ativar a carteira do bot" → `/real` (AC1.2.2). Nenhum outro motivo tem link (AC1.2.3).
- **Escolher "Real".** Muda o texto do botão de envio para "Continuar para a confirmação". Enviar abre o `RealConfirmDialog`.
- **Estado mudou entre abrir e enviar.** Se a API recusar com 4xx de domínio (AC1.2.8), o `RealConfirmDialog` fecha, o componente relê a elegibilidade, volta para "Simulado", mostra o motivo novo e põe o foco na mensagem de erro. É a mesma regra do `RealConfirmDialog` e do M2.4.
- **Opção indisponível com rádio nativo.** Os rádios são controlados (`checked={value === market}`). "Real" indisponível tem `aria-disabled="true"`, e não `disabled`, e continua na ordem das setas. Quando a seta, o clique ou o Espaço chegam em "Real", o `onChange` recusa a troca: o valor continua `PAPER`, o React devolve o rádio para desmarcado, o foco fica em "Real" e o motivo é lido pelo `aria-describedby`. Uma região `aria-live="polite"` repete "Real indisponível: <motivo>". Um teste de componente cobre a seta, o clique e o Espaço sobre "Real" indisponível (AC1.1.4, AC1.2.9).

### States

| State | Description | Trigger |
|---|---|---|
| default | "Simulado" marcado; "Real" disponível ou indisponível conforme a elegibilidade | a tela abre |
| hover | Cartão com o fundo `surface-raised` | o ponteiro sobre o cartão |
| focus | Contorno de 3 px `focus` no cartão do rádio com foco | Tab ou setas |
| selected | Borda de 3 px `accent` e o texto "✓ Selecionado" | o rádio marcado |
| disabled | "Real" com `aria-disabled="true"`, o texto "Indisponível" e o motivo embaixo | a elegibilidade diz indisponível |
| loading | "Real" com `aria-busy="true"`, `aria-disabled` e o texto "Conferindo se o modo real está disponível…" | a leitura da elegibilidade em andamento |
| error | "Real" indisponível com "Não foi possível conferir o modo real agora.", o código para o suporte e "Tentar de novo" | a leitura da elegibilidade falhou |
| empty | Não se aplica: as duas opções sempre aparecem | — |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| value | `'PAPER' \| 'REAL'` | yes | `'PAPER'` | O modo escolhido |
| onChange | `(market) => void` | yes | — | Chamado só com uma opção disponível |
| eligibility | `{ kind: 'loading' } \| { kind: 'error', error: RealError } \| { kind: 'ready', available: boolean, reason?: RealUnavailableReason, effectiveAt?: string }` | yes | — | A elegibilidade lida do servidor |
| onRetry | `() => void` | yes | — | Lê de novo a elegibilidade |
| timeZone | string | no | o fuso do navegador | Para a hora de fim da quarentena |

`RealUnavailableReason` vale `KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED` ou `QUARANTINE`. Os nomes são propostos; o contrato fica para o Functional Design.

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | Os cartões ficam empilhados, "Simulado" em cima, com a largura toda |
| 768–1.199 px | Os dois cartões ficam lado a lado, com a mesma largura |
| 1.200 px ou mais | Lado a lado, dentro da largura máxima de leitura do formulário |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `fieldset` com `legend` "Modo do bot"; dois `input type="radio"` nativos (`name="market"`); o cartão é o `label` do rádio |
| Keyboard interaction | Tab entra no grupo pelo rádio marcado; as setas trocam a opção; a seta para "Real" indisponível leva o foco até ele, mas o `onChange` recusa a marcação, "Simulado" continua marcado e o motivo é lido (ver "Comportamento"); Tab sai para o link do motivo, quando existe, e depois para "Estratégia modelo" |
| Label / aria-label | O nome visível ("Simulado" ou "Real") junto com o texto do selo; a descrição do cartão e o motivo ligados por `aria-describedby` |
| Contrast ratio | `text`/`surface` 14,45:1; `textMuted`/`surface` 8,83:1; borda de seleção `accent`/`surface` 8,33:1 |
| Screen reader | "Real, rádio, indisponível, 2 de 2. A carteira do bot não está ativa." / "Simulado, rádio, marcado, 1 de 2. Opera com saldo virtual em US$…" |
| Focus management | Na abertura, o foco vai para o h1 da tela, como hoje no `BotsShell`. Depois de uma recusa 4xx, o foco vai para a mensagem de erro (`role="alert"`) |

### Usage Example

```
<ModeStep
  value={market}
  onChange={setMarket}
  eligibility={realEligibility}
  onRetry={reloadEligibility}
/>
```

---

## RealConfirmDialog

| Field | Value |
|---|---|
| Component | RealConfirmDialog |
| Description | Confirmação explícita "Este bot opera com dinheiro real" antes de criar um bot REAL (US1.3) |
| Category | feedback |

### Comportamento

- **Base.** É o `ModalDialog` que já existe, com `role="alertdialog"`, o título "Confirmar bot real" e o `RealBadge`.
- **Corpo.** A frase fixa "Este bot opera com dinheiro real." (AC1.3.1) e o resumo: nome, estratégia e modo. O teto só aparece ("Teto da conta: …" ou "não definido") quando os limites REAL do M3b já foram lidos na tela; o diálogo não faz um pedido só para ele.
- **Caixa de confirmação.** "Confirmo: este bot opera com dinheiro real e as ordens dele usam o SOL da carteira do bot."
- **Botão "Criar bot real".** Fica desabilitado enquanto a caixa está desmarcada [Q2]. O texto "Marque a confirmação para criar o bot real." fica visível e descreve o botão (AC1.3.2). O texto segue o `activation.continueDisabled` que já existe.
- **Envio.**
  - Envia `market = REAL` com uma `Idempotency-Key` criada quando o diálogo abre.
  - O primeiro clique desabilita os dois botões e mostra "Criando o bot real…".
  - Um duplo clique ou um "Tentar de novo" reusa a mesma chave (AC1.3.5).
- **Sucesso.** O diálogo fecha e a tela vai para a etapa 2 do assistente do bot novo (AC1.3.3).
- **Recusa de domínio (4xx com código).** O diálogo fecha sem criar nada. O `ModeStep` relê a elegibilidade, volta para "Simulado" e mostra "O bot real não foi criado." com o motivo novo e o código, em `role="alert"`. Não há "Tentar de novo", porque repetir não muda o resultado.
- **Erro técnico (5xx, tempo esgotado, sem conexão).** O diálogo continua aberto com "Não foi possível criar o bot real. Tente de novo.", o código e o botão "Tentar de novo", que reusa a mesma `Idempotency-Key`.
- **Esc e "Cancelar".** Fecham sem criar nada. Eles ficam bloqueados só enquanto o pedido está em andamento.
- **Fundo.** Clicar no fundo não fecha o diálogo, porque é uma confirmação crítica.

### States

| State | Description | Trigger |
|---|---|---|
| default | Aberto, caixa desmarcada, "Criar bot real" desabilitado com o texto do que falta | "Continuar para a confirmação" com "Real" |
| hover | Botões e caixa com o realce padrão | o ponteiro |
| focus | Contorno `focus` no elemento ativo; o foco fica preso no diálogo | Tab ou Shift+Tab |
| disabled | "Criar bot real" desabilitado | a caixa desmarcada, ou o pedido em andamento |
| loading | "Criando o bot real…"; os dois botões desabilitados | o envio em andamento |
| error | Erro técnico: `RealErrorNotice` dentro do diálogo, com o texto, o código e "Tentar de novo" (mesma chave). Recusa de domínio: o diálogo fecha e o erro aparece no `ModeStep` | 5xx ou falha de rede; 4xx de domínio |
| empty | Não se aplica | — |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| draft | `{ name: string; templateName: string }` | yes | — | O resumo mostrado |
| cap | `bigint \| Unknown` | no | — | O teto da conta REAL, em lamports, só quando os limites do M3b já foram lidos; sem a prop, a linha não aparece |
| onConfirm | `(idempotencyKey: string) => Promise<void>` | yes | — | Cria o bot com `market = REAL` |
| onClose | `() => void` | yes | — | Fecha sem efeito |
| returnFocusTo | `HTMLElement \| null` | yes | — | O botão que abriu o diálogo |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | Largura de `100vw − 32px` (o `.real-dialog` atual); os botões ficam empilhados, com "Criar bot real" embaixo e com largura toda |
| 768–1.199 px | Largura máxima de 560 px; os botões ficam lado a lado, à direita |
| 1.200 px ou mais | Igual ao tablet |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `dialog` nativo com `showModal()` e `role="alertdialog"`, `aria-modal="true"`, `aria-labelledby` para o título e `aria-describedby` para a frase "Este bot opera com dinheiro real." |
| Keyboard interaction | Tab e Shift+Tab circulam dentro do diálogo; Espaço marca a caixa; Enter ou Espaço ativam o botão; Esc fecha sem efeito, exceto durante o envio |
| Label / aria-label | A caixa tem `label` visível; o botão desabilitado tem `aria-describedby` para "Marque a confirmação para criar o bot real." |
| Contrast ratio | `text`/`surface` 14,45:1; o selo `background`/`real` 8,80:1; o botão primário segue o tema atual |
| Screen reader | Na abertura: "Confirmar bot real, diálogo. Este bot opera com dinheiro real." Ao marcar a caixa, o botão passa a ser lido como disponível. O erro é anunciado por `role="alert"` |
| Focus management | O primeiro foco vai para "Cancelar", como no `MoneyConfirmDialog`; ao fechar, o foco volta para "Continuar para a confirmação"; depois de um erro, o foco vai para a mensagem |

### Usage Example

```
<RealConfirmDialog
  draft={{ name, templateName }}
  onConfirm={(key) => createBot({ templateId, name, market: 'REAL' }, key)}
  onClose={closeDialog}
  returnFocusTo={continueButton.current}
/>
```

---

## SimulatedBadge

| Field | Value |
|---|---|
| Component | SimulatedBadge |
| Description | Selo `◇ SIMULADO`, o par do `RealBadge`: marca um bot, um bloco ou um valor do modo PAPER [Q4] |
| Category | display |

### Comportamento

- **Só exibição.** O selo não tem interação.
- **Diferença do `RealBadge` além da cor.**
  - o ícone é ◇ (losango vazado), e não ◆;
  - o estilo é contorno sem preenchimento, e não preenchimento âmbar;
  - o texto é `SIMULADO`.
- **Texto.** É sempre `SIMULADO`, em maiúsculas, igual ao valor que o AC2.1.2 confere.
- **Substitui:**
  - a etiqueta `.tag` "MODO PAPER (simulado)" nos títulos de `/bots` e `/pnl`;
  - o `paperTag` "PAPER" do `AppShell` dentro das telas deste intent.
- **Onde aparece.** Nas linhas e cartões da lista, nos blocos de `/pnl`, no painel, no detalhe do bot e na comparação.

### States

| State | Description | Trigger |
|---|---|---|
| default | `◇ SIMULADO` com contorno | sempre |
| hover | Não se aplica: não é interativo | — |
| focus | Não se aplica: não é focável | — |
| disabled | Não se aplica | — |
| loading | Não se aplica: o selo aparece mesmo com o valor carregando | — |
| error | Não se aplica | — |
| empty | Não se aplica | — |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| testId | string | no | `'simulated-badge'` | O id para os testes, como no `RealBadge` |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | O mesmo selo; não quebra a linha (`white-space: nowrap`) |
| 768–1.199 px | O mesmo |
| 1.200 px ou mais | O mesmo |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `span`, sem papel; o ícone fica com `aria-hidden="true"` |
| Keyboard interaction | Nenhuma |
| Label / aria-label | O texto visível `SIMULADO` é o nome lido |
| Contrast ratio | Texto `text` sobre `surface` 14,45:1 (sobre `surface-raised` 13,05:1); contorno de 1 px em `text-muted` (8,83:1, acima de 3:1). O token `border` (1,67:1) **não** serve para o contorno |
| Screen reader | Lê "SIMULADO"; nas linhas, junto do nome do bot |
| Focus management | Não recebe foco |

### Usage Example

```
<h2>Simulado, em dólares <SimulatedBadge testId="pnl-paper-badge" /></h2>
```

---

## PnlModeBlock

| Field | Value |
|---|---|
| Component | PnlModeBlock |
| Description | O bloco de um modo: título com selo, total com o rótulo de modo e moeda, realizado, não realizado, taxas, as linhas dos bots, a linha "Sem bot" e o aviso do simulado (US4.1, US3.1, US3.2, US3.8, US3.9, US4.2, US4.4) |
| Category | display |

### Comportamento

- **Um modo só.** O bloco recebe só o P&L de um modo, `PAPER` ou `REAL`. Ele nunca lê o outro modo, e não existe prop nem variante que receba os dois (AC4.1.2, AC4.1.4).
- **Variante `full` (`/pnl`).** Mostra:
  - o total, o realizado, o não realizado e as taxas;
  - a linha "Não realizado: posições abertas agora (marcação às HH:MM)";
  - a lista dos bots do modo e a linha "Sem bot";
  - o `ProratedLines` por bot real;
  - o aviso do simulado no PAPER.
- **Variante `compact` (painel).** Mostra o total, o realizado, as taxas, o aviso no PAPER e o link "Ver o resultado simulado" ou "Ver o resultado real" para `/pnl` (AC4.3.1, AC4.3.4).
- **Variante `bot` (detalhe do bot).** Mostra o total, o realizado, o não realizado e as taxas de um bot só.
- **Moeda.**
  - PAPER sempre em US$.
  - REAL sempre em SOL.
  - Os números cortam os zeros à direita, sem arredondar, com pelo menos 2 casas (pergunta OQ1 de `mockups.md`).
- **Linhas dos bots.**
  - Mostram o nome (link para o detalhe), o estado em texto ("Ativo", "Pausado" ou "Removido") e o total.
  - Com 1.200 px ou mais, também o realizado, o não realizado e as taxas.
  - A ordem é a da criação. Não há ordenação por valor.
- **"Sem bot".** Aparece só quando é diferente de zero, com "Motivo: <texto>". Não é link.
- **Aviso do simulado.** Aparece no bloco PAPER sempre que ele mostra algum valor. Não aparece no bloco REAL nem no PAPER vazio (AC4.2.1, AC4.2.6).
- **Valores desconhecidos.**
  - Um não realizado `unknown` faz o total ficar "desconhecido (depende do não realizado)".
  - O realizado e as taxas continuam exatos (AC3.8.3).
  - O aviso "◐ Dado parcial: há posição sem preço confiável." aparece, com símbolo, rótulo e borda (`unknown`) diferentes do aviso do simulado (⚠, `warning`).
- **Estados independentes.** Cada bloco tem carregar, erro e "Tentar de novo" próprios. O erro de um nunca muda o outro [Q3].

### States

| State | Description | Trigger |
|---|---|---|
| default | Total, partes e linhas dos bots | a leitura concluída |
| hover | Linhas dos bots com o realce no link do nome | o ponteiro |
| focus | Contorno `focus` no link do bot, no botão do `ProratedLines` ou em "Tentar de novo" | Tab |
| disabled | Não se aplica | — |
| loading | Título e selo visíveis; o esqueleto de 2 linhas com "Calculando o resultado simulado…" ou "Calculando o resultado real…" em `role="status"`; `aria-busy="true"`; o valor nunca é zero | a leitura em andamento |
| error | `RealErrorNotice` com "Não foi possível carregar o resultado simulado/real.", o código e "Tentar de novo", que repete só este bloco | a leitura falhou |
| empty | PAPER: "Nenhuma operação simulada neste período." e "→ Criar bot simulado". REAL: "Sem bots reais." e o link conforme o motivo ("Criar bot real", "Ativar a carteira do bot" ou só o texto do motivo) | o modo sem bots ou sem operações no período |
| unknown | O total "desconhecido (depende do não realizado)"; o não realizado "desconhecido (<motivo>)"; o aviso "◐ Dado parcial" | `unrealized` com `status = 'UNKNOWN'` |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| market | `'PAPER' \| 'REAL'` | yes | — | O modo do bloco; define o título, o selo, a moeda e o aviso |
| variant | `'full' \| 'compact' \| 'bot'` | no | `'full'` | O lugar onde o bloco aparece |
| load | `Load<ModePnl>` | yes | — | `loading`, `error` com `RealError`, ou `ready` com o P&L do modo |
| onRetry | `() => void` | yes | — | Repete só a leitura deste modo |
| period | `PeriodLabel` | no | — | O texto do período mostrado |
| realUnavailable | `RealUnavailableReason` | no | — | Só no REAL vazio: escolhe o link ou o texto do motivo |
| timeZone | string | no | o fuso do navegador | Para "marcação às HH:MM" |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | Cartão com a largura toda; os blocos ficam empilhados, PAPER e depois REAL; o valor do total fica numa linha própria; as linhas dos bots ficam em duas linhas (nome; estado e total) |
| 768–1.199 px | Os dois blocos ficam lado a lado em grade de 2 colunas iguais; cada linha de bot mostra o nome, o estado e o total |
| 1.200 px ou mais | Lado a lado dentro da largura máxima de 1.200 px; a lista de bots vira tabela com as colunas Bot, Estado, Realizado, Não realizado, Taxas e Total |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `section` com `aria-labelledby` para o h2 (`/pnl`) ou o h3 (painel); as partes ficam numa `dl`; a lista de bots é uma `ul` (ou `table` com `caption` "Bots simulados" ou "Bots reais" e `th scope="col"`, a partir de 1.200 px) |
| Keyboard interaction | Tab percorre só os elementos ativos: o link de cada bot, o botão do `ProratedLines` e "Tentar de novo"; não há elemento focável só para leitura |
| Label / aria-label | O total tem o rótulo "Total simulado, em dólares" ou "Total real, em SOL" (`aria-labelledby` para um texto visível "Total" junto do nome do modo no h2) (AC4.4.3) |
| Contrast ratio | Ganho `accent`/`surface` 8,33:1; perda `danger`/`surface` 7,63:1; desconhecido `unknown`/`surface` 9,77:1; aviso `warning`/`surface` 11,67:1; texto secundário `textMuted`/`surface` 8,83:1 |
| Screen reader | O total fica numa região `aria-live="polite"` e `aria-atomic="true"`: "Total real, em SOL: +0,0312 SOL, ganho". É anunciado quando o valor muda depois da carga inicial (troca de período, "Tentar de novo" ou preço que volta), no máximo uma vez a cada 30 s por bloco. A carga inicial não é anunciada |
| Focus management | O bloco não move o foco por conta própria. Depois de "Tentar de novo", o aviso de erro some; para o foco não se perder, ele vai para o h2 do bloco, que tem `tabIndex={-1}` |

### Usage Example

```
<PnlModeBlock market="PAPER" load={paperPnl.load} onRetry={paperPnl.reload} period={periodLabel} />
<PnlModeBlock market="REAL" load={realPnl.load} onRetry={realPnl.reload} period={periodLabel}
              realUnavailable={eligibility.reason} />
```

---

## ProratedLines

| Field | Value |
|---|---|
| Component | ProratedLines |
| Description | As parcelas rateadas de um bot real ("Fechado por liquidação", "Fechado por condicional", "Vendido por outro bot"), com o resumo visível e o detalhe num bloco que abre e fecha (US3.3, US3.4, US3.5) [Q2] |
| Category | display |

### Comportamento

- **Resumo.** Sempre visível, uma linha por origem com parcela: "inclui +0,0050 SOL fechado por liquidação". O valor é a soma do realizado das parcelas daquela origem, já calculada pela API. A tela não soma.
- **Botão.** "Ver N linhas rateadas" abre a lista; "Ocultar N linhas rateadas" fecha. N é o número de parcelas.
- **Cada parcela mostra:**
  - a data no fuso da pessoa;
  - o token;
  - a origem em texto;
  - o recebido;
  - a taxa;
  - o realizado da parcela.

  Valores em SOL.
- **Ordem.** Da mais recente para a mais antiga. Mais de 10 parcelas mostram "Ver todas no detalhe do bot", com link para `/bots/{id}`.
- **Bot sem parcelas.** O componente não aparece (AC3.3.4, AC3.5.3).
- **Estado aberto.** Fica só na sessão da página. Não é guardado.

### States

| State | Description | Trigger |
|---|---|---|
| default | Fechado: o resumo e o botão "Ver N linhas rateadas" | a renderização |
| hover | Realce do botão | o ponteiro |
| focus | Contorno `focus` no botão | Tab |
| disabled | Não se aplica | — |
| loading | Não se aplica: as parcelas chegam com o P&L do bloco; enquanto o bloco carrega, o componente não aparece | — |
| error | Não se aplica: o erro é do `PnlModeBlock` | — |
| empty | O componente não aparece | um bot sem parcelas |
| expanded | A lista de parcelas visível; o botão com "Ocultar…" | o clique, Enter ou Espaço |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| botName | string | yes | — | Para o nome acessível do botão |
| botId | string | yes | — | Para o link "Ver todas no detalhe do bot" |
| summary | `Array<{ origin: ProratedOrigin; realized: bigint }>` | yes | — | O resumo por origem, vindo da API |
| lines | `Array<ProratedLine>` | yes | — | As parcelas: instante UTC, mint, origem, recebido, taxa, realizado |
| timeZone | string | no | o fuso do navegador | Para as datas |

`ProratedOrigin` vale `LIQUIDATION`, `CONDITIONAL` ou `SOLD_BY_OTHER_BOT`. Os textos são "Fechado por liquidação", "Fechado por condicional" e "Vendido por outro bot".

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | Cada parcela vira um cartão com rótulos ("Recebido", "Taxa", "Realizado") |
| 768–1.199 px | Igual ao celular, dentro da coluna do bloco REAL |
| 1.200 px ou mais | As parcelas ficam numa tabela com `caption` e as colunas Data, Token, Origem, Recebido, Taxa e Realizado |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `button` com `aria-expanded` e `aria-controls` para a região da lista; a lista é `ul` ou `table` |
| Keyboard interaction | Tab chega ao botão; Enter ou Espaço abrem e fecham; quando aberto, o próximo Tab entra no link "Ver todas…", se existir |
| Label / aria-label | O nome do botão inclui o bot: "Ver 1 linha rateada de Momentum A" (texto visível mais um trecho com `visually-hidden`) |
| Contrast ratio | `text`/`surface` 14,45:1; `textMuted`/`surface` 8,83:1 |
| Screen reader | O resumo é texto comum; a origem é lida em texto; o ícone ▸/▾ fica com `aria-hidden` |
| Focus management | O foco fica no botão ao abrir e ao fechar |

### Usage Example

```
<ProratedLines botName={bot.name} botId={bot.botId} summary={bot.prorated.summary}
               lines={bot.prorated.lines} timeZone={zone} />
```

---

## PeriodSelector

| Field | Value |
|---|---|
| Component | PeriodSelector |
| Description | O período do P&L: "Desde o início" (padrão), "Últimos 7 dias", "Últimos 30 dias" ou "Intervalo", no fuso da pessoa, enviado em UTC (US7.1, US7.2) [Q7] |
| Category | input |

### Comportamento

- **Padrão.** "Desde o início" (AC7.1.1).
- **Opções fixas.** As três opções fixas valem assim que são marcadas, sem botão. Os campos de data ficam escondidos.
- **Limites de cada opção** [Q7]:
  - "Últimos 7 dias": de 00:00 do dia (hoje − 6) no fuso da pessoa até agora.
  - "Últimos 30 dias": de 00:00 do dia (hoje − 29) até agora.
  - "Intervalo": de 00:00 do dia inicial até 00:00 do dia seguinte ao final, com os dois dias inteiros. É a regra do `toUtcPeriod` que já existe, com o máximo de 366 dias.
- **"Intervalo".** Mostra os campos "De" e "Até (inclusive)" e o botão "Aplicar". O período só muda com "Aplicar".
- **Validação ao aplicar** (AC7.1.4):
  - Data final antes da inicial: "A data final vem antes da inicial. Corrija o campo "Até"." no campo "Até".
  - Mais de 366 dias: "Escolha um período de até 366 dias.".
  - Data vazia ou inválida: "Informe a data no formato dd/mm/aaaa." no campo com erro.
  - As datas digitadas são mantidas, e os blocos continuam com o último período válido.
- **Instantes.** São calculados no fuso do navegador e enviados à API em UTC (AC7.2.3). O texto "Mostrando: …" mostra o período no fuso da pessoa e lembra que o não realizado é o de agora (AC7.2.1).
- **Endereço.** O período escolhido fica no endereço (`?periodo=7d`, `30d`, `intervalo&de=AAAA-MM-DD&ate=AAAA-MM-DD`). Sem parâmetro, vale "Desde o início".

### States

| State | Description | Trigger |
|---|---|---|
| default | "Desde o início" marcado; as datas escondidas | a tela abre |
| hover | Realce das opções | o ponteiro |
| focus | Contorno `focus` no rádio, no campo de data ou em "Aplicar" | Tab ou setas |
| disabled | Não se aplica: o período pode mudar mesmo com um bloco carregando (o pedido anterior é descartado) | — |
| loading | O seletor continua usável; os blocos mostram o próprio carregando | o pedido em andamento |
| error | A mensagem do campo, com `aria-invalid="true"` no campo | a validação do intervalo falhou |
| empty | Não se aplica | — |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| value | `{ kind: 'ALL' } \| { kind: 'LAST_7' } \| { kind: 'LAST_30' } \| { kind: 'RANGE'; from: string; to: string }` | yes | `{ kind: 'ALL' }` | O período escolhido, em dias de calendário locais |
| onChange | `(period, utc: UtcPeriod \| null) => void` | yes | — | `utc` é `null` para "Desde o início" |
| timeZone | string | no | o fuso do navegador | O fuso dos dias |
| now | `() => Date` | no | o relógio do app | O relógio injetável, para os testes |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | As opções ficam empilhadas, uma por linha; os campos "De" e "Até" ficam empilhados; "Aplicar" tem a largura toda |
| 768–1.199 px | As opções ficam numa linha, que quebra se faltar espaço; os campos ficam lado a lado |
| 1.200 px ou mais | Igual ao tablet |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `fieldset` com `legend` "Período" e quatro `input type="radio"` nativos; os campos `input type="date"` com `label` |
| Keyboard interaction | Tab entra no rádio marcado; as setas trocam e aplicam a opção; com "Intervalo", Tab vai para "De", "Até" e "Aplicar"; Enter num campo de data aplica, porque é um `form` |
| Label / aria-label | Rótulos visíveis; a ajuda "Datas no seu fuso (<fuso>)." ligada por `aria-describedby` aos campos |
| Contrast ratio | `text`/`surface` 14,45:1; o erro `danger`/`surface` 7,63:1, sempre com "!" e texto |
| Screen reader | O texto "Mostrando: …" fica numa região `aria-live="polite"` e anuncia o período aplicado; o erro de campo é lido pelo `aria-describedby` e anunciado por `role="alert"` ao aplicar |
| Focus management | Ao trocar a opção, o foco fica no rádio; depois de "Aplicar" com erro, o foco vai para o primeiro campo com erro; com sucesso, o foco fica em "Aplicar" |

### Usage Example

```
<PeriodSelector value={period} onChange={(p, utc) => { setPeriod(p); reloadBoth(utc); }} timeZone={zone} />
```

---

## BotModeFilter

| Field | Value |
|---|---|
| Component | BotModeFilter |
| Description | O filtro da lista de bots por modo: "Todos", "Simulado" ou "Real" (US2.3) |
| Category | input |

### Comportamento

- **Padrão.** "Todos" (AC2.3.1).
- **Efeito imediato.** Marcar uma opção filtra a lista na hora, sem botão (AC2.3.2, AC2.3.3).
- **Contagem.** A linha "Mostrando N de M bots." aparece com "Simulado" ou "Real".
- **Endereço.** O filtro fica no endereço (`?modo=simulado` ou `?modo=real`).
- **Filtro de estado.** O filtro de modo trabalha junto com o "Filtrar por estado" que já existe. Os dois filtros se combinam.
- **Aviso do simulado.** Com "Real", o aviso some (AC4.2.6).
- **Lista vazia pelo filtro** (AC2.3.4):
  - "Você não tem bots reais." ou "Você não tem bots simulados.";
  - o botão "Ver todos os bots", que volta para "Todos".
- **Sem bots.** Quando a pessoa não tem nenhum bot, o filtro não aparece.
- **Onde filtra.** Pode filtrar na tela ou pedir à API; a escolha fica para o Functional Design. O comportamento visível é o mesmo.

### States

| State | Description | Trigger |
|---|---|---|
| default | "Todos" marcado | a tela abre sem `?modo` |
| hover | Realce da opção | o ponteiro |
| focus | Contorno `focus` no rádio | a abertura da tela, Tab ou setas |
| disabled | Não se aplica | — |
| loading | O filtro continua usável enquanto a lista carrega | o pedido da lista |
| error | Não se aplica: o erro é da lista | — |
| empty | O filtro some quando a pessoa não tem nenhum bot | a lista vazia sem filtro |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| value | `'ALL' \| 'PAPER' \| 'REAL'` | yes | `'ALL'` | O filtro escolhido |
| onChange | `(value) => void` | yes | — | Filtra a lista e atualiza o endereço |
| shown | number | no | — | N em "Mostrando N de M bots." |
| total | number | no | — | M |
| autoFocus | boolean | no | `true` em `/bots` | O foco começa no filtro (AC2.3.5) |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | As três opções ficam empilhadas, cada uma com no mínimo 44 px de altura |
| 768–1.199 px | As opções ficam numa linha, com o filtro de estado ao lado |
| 1.200 px ou mais | Igual ao tablet |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `fieldset` com `legend` "Modo" e três `input type="radio"` nativos (`name="bot-mode-filter"`) |
| Keyboard interaction | Quando `/bots` abre, o foco vai para o rádio marcado (AC2.3.5); as setas trocam e aplicam a opção; Tab sai para o filtro de estado e depois para a lista |
| Label / aria-label | Rótulos visíveis "Todos", "Simulado" e "Real" |
| Contrast ratio | `text`/`background` 15,68:1 |
| Screen reader | "Mostrando N de M bots." fica numa região `aria-live="polite"`; o texto de lista vazia pelo filtro também é anunciado |
| Focus management | O foco começa no filtro; trocar a opção não move o foco; "Ver todos os bots" devolve o foco ao rádio "Todos" |

### Usage Example

```
<BotModeFilter value={modeFilter} onChange={setModeFilter} shown={visible.length} total={all.length} />
```

---

## ComparisonView

| Field | Value |
|---|---|
| Component | ComparisonView |
| Description | Vista em `/pnl` que compara lado a lado um bot simulado e um bot real da mesma estratégia, cada um na sua moeda, sem diferença calculada (US8.1) [Q1] |
| Category | layout |

### Comportamento

- **Abertura.** Fica fechada, num bloco que abre e fecha: "Comparar um bot simulado com um bot real".
- **Seleção dos bots.**
  - Há dois `select`: "Bot simulado" (bots PAPER) e "Bot real" (só os bots REAL da mesma estratégia do bot simulado escolhido).
  - Trocar o bot simulado limpa o bot real quando a estratégia não bate.
  - Com um único par possível, os dois já vêm escolhidos.
- **Cada lado.** É um `PnlModeBlock` na variante `bot` com o estado e a data de criação. Usa o mesmo período do `PeriodSelector`.
- **Nada calculado entre os lados.** Não há diferença, percentual, ranking nem destaque de "melhor" (AC8.1.2). Uma frase fixa diz: "Os valores ficam cada um na sua moeda e não são comparados por conta."
- **Aviso.** Só no lado simulado (AC8.1.3).
- **Endereço.** A escolha fica no endereço (`?comparar=<idPaper>,<idReal>`). Abrir com o parâmetro já abre a vista.
- **Id de outra pessoa ou inexistente.** A API devolve 404. O lado mostra "Bot não encontrado." sem nenhum dado e o `select` volta para vazio (AC8.1.4).
- **Id do modo errado.** Um id PAPER no lugar do REAL, ou o contrário, é tratado como "Bot não encontrado.".

### States

| State | Description | Trigger |
|---|---|---|
| default | Fechada: o botão "Comparar um bot simulado com um bot real" | a tela abre sem `?comparar` |
| hover | Realce do botão e dos `select` | o ponteiro |
| focus | Contorno `focus` no botão, nos `select` e em "Tentar de novo" | Tab |
| disabled | "Bot real" desabilitado, com o texto "Escolha primeiro o bot simulado." | nenhum bot simulado escolhido |
| loading | "Carregando a comparação…" em cada lado, cada um com `aria-busy` | a leitura de cada bot |
| error | O `RealErrorNotice` só no lado que falhou, com o código e "Tentar de novo" | a leitura de um lado falhou |
| empty | "Para comparar, você precisa de um bot simulado e de um bot real da mesma estratégia." e "+ Novo bot" | nenhum par possível |
| not-found | "Bot não encontrado." no lado, sem dados | 404 |
| unknown | O lado REAL ou PAPER com o não realizado e o total "desconhecido" | o preço `unknown` |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| paperBots | `BotSummary[]` | yes | — | Os bots PAPER da pessoa |
| realBots | `BotSummary[]` | yes | — | Os bots REAL da pessoa |
| selection | `{ paperId?: string; realId?: string }` | yes | — | A escolha, lida do endereço |
| onSelect | `(selection) => void` | yes | — | Atualiza o endereço |
| period | `UtcPeriod \| null` | yes | — | O mesmo período dos blocos |
| timeZone | string | no | o fuso do navegador | Para as datas |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | Os `select` ficam empilhados; os lados ficam empilhados, simulado e depois real |
| 768–1.199 px | Os dois lados ficam em 2 colunas iguais |
| 1.200 px ou mais | Igual ao tablet, dentro da largura máxima de 1.200 px |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `button` com `aria-expanded` e `aria-controls` para a região; a região é uma `section` com h2 "Comparação"; cada lado é uma `section` com h3 "Momentum A, simulado, em dólares" ou "Momentum A, real, em SOL" |
| Keyboard interaction | Enter ou Espaço abrem e fecham; Tab percorre os dois `select` e os elementos ativos dos lados; os `select` são nativos |
| Label / aria-label | Rótulos visíveis "Bot simulado" e "Bot real"; a ajuda "Só aparecem bots reais da mesma estratégia…" ligada por `aria-describedby` ao `select` "Bot real" |
| Contrast ratio | Os mesmos do `PnlModeBlock` |
| Screen reader | O total de cada lado tem o rótulo com o modo e a moeda ("Total do bot simulado, em dólares") e fica em `aria-live="polite"` quando muda a escolha |
| Focus management | Ao abrir, o foco vai para o `select` "Bot simulado"; ao fechar, volta ao botão; depois de "Bot não encontrado.", o foco vai para o `select` do lado |

### Usage Example

```
<ComparisonView paperBots={paper} realBots={real} selection={fromQuery} onSelect={toQuery}
                period={utcPeriod} timeZone={zone} />
```

---

## BotPnlLines (componente novo do detalhe do bot)

Este componente não está na lista do pedido. Ele é necessário para a coluna de origem do detalhe (US3.10), por isso tem uma especificação curta.

| Field | Value |
|---|---|
| Component | BotPnlLines |
| Description | A tabela das linhas que formam o P&L de um bot real, com a data no fuso da pessoa, o lado, o token, o resultado em SOL, a taxa e a origem em texto (US3.10) |
| Category | display |

### States

| State | Description | Trigger |
|---|---|---|
| default | A tabela das linhas, da mais recente para a mais antiga, com "Carregar mais" | a leitura concluída |
| hover | Realce da linha | o ponteiro |
| focus | Contorno `focus` em "Carregar mais" e em "Tentar de novo" | Tab |
| disabled | "Carregar mais" desabilitado enquanto carrega a próxima página | a paginação |
| loading | "Carregando as linhas do P&L…" (`role="status"`) | a leitura |
| error | O `RealErrorNotice` com o código e "Tentar de novo" | a leitura falhou |
| empty | "Nenhuma operação real até agora." | nenhuma linha |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| botId | string | yes | — | O bot (o tenant vem do token, no servidor) |
| botName | string | yes | — | Para o `caption` |
| timeZone | string | no | o fuso do navegador | Para as datas (AC7.2.2) |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| 360–767 px | Cada linha vira um cartão com rótulos (padrão `real-orders-cards`) |
| 768–1.199 px | Tabela; a coluna "Origem" quebra a linha |
| 1.200 px ou mais | Tabela inteira |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `table` com `caption` "Linhas do P&L de <bot>" e `th scope="col"` em cada coluna (AC3.10.4) |
| Keyboard interaction | Só "Carregar mais" e "Tentar de novo" são focáveis |
| Label / aria-label | Cabeçalhos "Data (seu fuso)", "Lado", "Token", "Resultado (SOL)", "Taxa (SOL)" e "Origem"; nos cartões, cada valor tem o seu rótulo visível |
| Contrast ratio | `text`/`surface` 14,45:1 |
| Screen reader | A origem é lida em texto ("Fechado por liquidação"); "—" no resultado de uma compra é lido como "sem resultado" (`aria-label`) |
| Focus management | Depois de "Carregar mais", o foco vai para a primeira linha nova (`tabIndex={-1}`) |

### Usage Example

```
<BotPnlLines botId={bot.botId} botName={bot.name} timeZone={zone} />
```

---

## Componentes que já existem e mudam

| Componente | Mudança | Fonte |
|---|---|---|
| `BotCreate` | Ganha o `ModeStep` antes da estratégia, o indicador "Etapa 1 de 5: Modo e nome" e o envio de `market`; no REAL, abre o `RealConfirmDialog` | US1.1 a US1.3 [Q1] |
| `BotWizard` | Indicador "Etapa N de 5"; a linha "Modo: … · não pode ser trocado depois da criação"; no REAL, a etapa 3 mostra os limites REAL só para leitura, sem saldo virtual (M3b, decisão D1); "Iniciar bot simulado" ou "Iniciar bot real" | US1.3, US1.4 |
| `BotsShell` | O h1 sem a etiqueta "MODO PAPER (simulado)"; na lista, o h1 é "Meus bots"; o selo de modo vai no título das telas de um bot só | US2.1 |
| `BotList` | O `BotModeFilter`; a coluna "Modo" com o selo; o P&L na moeda do modo; o aviso do simulado; tabela a partir de 768 px e cartões abaixo; o aviso do US6.2 | US2.1 a US2.3, US4.2, US6.2 |
| `BotDetail` | O selo e o modo em texto no cabeçalho; o `PnlModeBlock` `bot`; o `BotPnlLines` no REAL; o aviso no PAPER | US3.10, US4.2, US1.4 |
| `PnlScreen` | O h1 "Resultado"; o `PeriodSelector` com "Desde o início" como padrão (hoje são 7 dias); os dois `PnlModeBlock`; o `ComparisonView`; a conferência e o ledger PAPER embaixo, com o selo | US4.1, US7.1, US8.1 |
| `DashboardScreen` | O `PnlCard` e o `DashboardRealPnl` dão lugar à região "Resultados por modo" com dois `PnlModeBlock` `compact` | US4.3 |
| `PnlAmountView` | O texto `unknown` mostrado à pessoa passa a ser "desconhecido" | US3.8 [Q3] |
| `RealErrorNotice` | Passa a ser usado também pelos blocos PAPER, para mostrar o código para o suporte em todos os erros | [Q3] |

## Assumptions & Open Questions

**Suposições** [assumption]:
1. **"Real" indisponível com `aria-disabled`.** O cartão usa `aria-disabled` em vez de `disabled`, para que a pessoa com leitor de tela chegue ao motivo pelo teclado. A escolha continua bloqueada.
2. **Anúncio dos totais.** O `aria-live` dos totais não anuncia a carga inicial e anuncia no máximo uma vez a cada 30 s por bloco, para o canal ao vivo do painel não encher o leitor de tela.
3. **Foco em `/bots`.** Quando `/bots` abre, o foco vai para o filtro de modo, como pede o AC2.3.5, e não para o h1 como hoje no `BotsShell`. O h1 continua rotulando o `main`.
4. **Nomes dos tipos.** Os nomes `RealUnavailableReason`, `ProratedOrigin` e `ModePnl` são propostas. O contrato da API fica para o Functional Design.

**Perguntas em aberto:** consolidadas na lista única do fim de `mockups.md` (OQ1 a OQ7), cada uma com o dono e a opção recomendada.
