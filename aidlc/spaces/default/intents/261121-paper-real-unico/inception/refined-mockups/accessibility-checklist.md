# Checklist de acessibilidade — paper-real-unico

O nível é o do beta [Q5]:
- navegação por teclado e foco visível;
- rótulos visíveis;
- contraste mínimo de 4,5:1 no tema escuro;
- cor sempre acompanhada de texto ou sinal;
- rótulos para leitor de tela com o modo e a moeda;
- `aria-live` nas mudanças de total.

O WCAG 2.1 AA completo fica para a abertura ao público, como manda a correção de `project.md`. Não há portão automático de acessibilidade na CI (team-practices). Cada item abaixo é conferido por teste de componente (Jest com Testing Library) ou pelo spec de ponta a ponta, quando indicado.

## Sources

- `refined-mockups-questions.md`: Q5 (acessibilidade), Q6 (tamanhos de tela), Q3 (estados).
- `inception/user-stories/stories.md`: AC1.1.4, AC1.2.7, AC2.1.2, AC2.1.4, AC2.3.5, AC3.10.4, AC4.4.1 a AC4.4.5, AC4.5.1 a AC4.5.3, AC6.2.3.
- `inception/requirements-analysis/requirements.md`: NFR5 e NFR6.
- `ideation/rough-mockups/wireframes.md`: as notas de acessibilidade das telas 1 a 6.
- `aidlc/spaces/default/memory/project.md`, Corrections: a acessibilidade básica do beta.
- `inception/practices-discovery/team-practices.md`, Testing Posture: sem portão automático.
- `apps/web/src/lib/theme/tokens.ts` (`contrastRatio`, `textPairs`, conferidos por `tokens.test.ts`) e `globals.css` (`:focus-visible`, `.visually-hidden`).

## Contraste medido (tema escuro)

Os valores vêm da fórmula de `tokens.ts`. Todo texto dos mockups usa um destes pares.

| Texto | Fundo | Contraste | Uso |
|---|---|---|---|
| `text` | `surface` | 14,45:1 | Texto dos blocos, cartões e do `SimulatedBadge` |
| `text` | `surfaceRaised` | 13,05:1 | Diálogos e avisos |
| `textMuted` | `surface` | 8,83:1 | Descrições, ajuda, "Mostrando…" |
| `accent` | `surface` | 8,33:1 | Ganho e links |
| `danger` | `surface` | 7,63:1 | Perda e erros |
| `danger` | `surfaceRaised` | 6,89:1 | Erro dentro do diálogo (**par novo** em `textPairs`) |
| `warning` | `surface` | 11,67:1 | Aviso do simulado (⚠) |
| `unknown` | `surface` | 9,77:1 | "desconhecido"; borda do aviso "◐ Dado parcial" |
| `real` | `surface` | 8,11:1 | Bordas e textos âmbar do REAL |
| `background` | `real` | 8,80:1 | Texto do `RealBadge` |
| `focus` | `surface` | 12,07:1 | Contorno de foco (3 px) |

**Componentes de interface (3:1).** O contorno do `SimulatedBadge` e a borda do cartão selecionado usam `text-muted` (8,83:1) e `accent` (8,33:1). O token `border` (1,67:1) só aparece em divisórias decorativas. Ele nunca indica estado.

**Teste.** O par novo `['danger', 'surfaceRaised']` entra em `textPairs`, para o `tokens.test.ts` conferir o par.

## Regras para todas as telas

- [ ] **Cor nunca sozinha** (AC4.4.2, AC1.2.7, AC2.1.2):
  - ganho com "+" e "(ganho)";
  - perda com "−" e "(perda)";
  - desconhecido com a palavra "desconhecido" e o motivo;
  - erro com "!" e texto;
  - selecionado com "✓ Selecionado";
  - modo com o texto `SIMULADO` ou `REAL` e ícones de forma diferente (◇ vazado e ◆ cheio).
- [ ] **Foco visível.** Todo elemento interativo mostra o `:focus-visible` de 3 px em `focus`, com afastamento de 2 px. Nenhum `outline: none`.
- [ ] **Sem armadilha de teclado.** Só o `RealConfirmDialog` prende o foco, e ele fecha com Esc.
- [ ] **Estados distintos** (AC4.5.3, AC2.1.4): carregando, erro, `unknown` e vazio têm texto e aparência diferentes entre si:
  - carregando: esqueleto e "Calculando…" ou "Carregando…" em `role="status"`;
  - erro: `role="alert"`, "!", o código para o suporte e "Tentar de novo";
  - `unknown`: itálico em `unknown` e "desconhecido (<motivo>)";
  - vazio: frase e ação.
- [ ] **Rótulos com o modo e a moeda** (AC4.4.3):
  - todo total é lido com o modo e a moeda: "Total simulado, em dólares", "Total real, em SOL", "Resultado simulado" e "Resultado real";
  - todo valor de bot é lido junto com o nome e o selo do bot.
- [ ] **`aria-live` nos totais** [Q5]:
  - o total de cada bloco fica em `aria-live="polite"` e `aria-atomic="true"`;
  - a carga inicial não é anunciada;
  - uma mudança é anunciada no máximo uma vez a cada 30 s por bloco.
- [ ] **Ícones decorativos** (◇, ◆, ⓘ, ⚠, ◐, ▸, ▾) ficam com `aria-hidden="true"`. O texto ao lado carrega o sentido.
- [ ] **Língua.** `lang="pt-BR"` na página. Os textos de interface ficam em pt-BR (team.md).
- [ ] **Movimento.** Nenhuma animação passa de 200 ms, e há respeito a `prefers-reduced-motion`.
- [ ] **Toque.** Alvos de no mínimo 44 × 44 px abaixo de 768 px, com 8 px entre alvos vizinhos.

## Tamanhos de tela [Q6] (NFR6, AC4.5.1)

| Faixa | Disposição | Conferência |
|---|---|---|
| 360 a 767 px | Cartões empilhados; o filtro e o período em coluna; tabelas viram cartões com rótulos; os blocos ficam um embaixo do outro, Simulado e depois Real | `scrollWidth` do documento igual a `clientWidth` em 360 px, em `/bots`, `/pnl`, `/` e `/bots/{id}`, com nomes de 60 caracteres e valores de 9 casas |
| 768 a 1.199 px | Os blocos ficam lado a lado (2 colunas); a lista de bots vira tabela; cada linha de bot dentro do bloco mostra o nome, o estado e o total | Nenhum valor cortado; a coluna "Origem" quebra a linha |
| 1.200 px ou mais | Largura máxima de leitura de 1.200 px; a lista de bots dentro do bloco vira tabela completa | — |

- [ ] **Sem rolagem lateral em 360 px** (AC4.5.1). Nomes, endereços e valores longos quebram a linha (`overflow-wrap: anywhere`). O valor do total fica numa linha própria no celular.
- [ ] **Zoom de 200%** em 1.280 px: o resultado equivale à faixa de 768 a 1.199 px, sem perda de conteúdo.
- [ ] **A ordem visual é a ordem do DOM em todas as faixas:** Simulado antes de Real, filtro antes da lista. A grade não reordena com CSS (AC4.5.2).

---

## Por tela

### `/bots/new`: etapa "Modo" (M1) e `RealConfirmDialog` (M2)

| Item | Critério | Fonte |
|---|---|---|
| [ ] Estrutura | h1 "Novo bot"; `fieldset` e `legend` "Modo do bot"; rádios nativos com `label` | AC1.1.4 |
| [ ] Ordem do teclado | Pular para o conteúdo → h1 → rádio marcado do modo (setas entre "Simulado" e "Real") → link do motivo, quando existe → "Estratégia modelo" → "Nome do bot" → botão de envio | AC1.1.4 |
| [ ] Foco | Na abertura, no h1 (como hoje); depois de uma recusa 4xx, na mensagem de erro | — |
| [ ] Opção indisponível | "Real" com `aria-disabled="true"`, focável e na ordem das setas; a seta, o clique ou o Espaço não o marcam (o `onChange` recusa, "Simulado" continua marcado), o foco fica nele, o motivo é lido por `aria-describedby` e repetido em `aria-live="polite"` | AC1.2.2 a AC1.2.6, AC1.1.4 |
| [ ] Recusa 4xx | O diálogo fecha, a escolha volta para "Simulado", o motivo novo aparece e o foco vai para a mensagem (`role="alert"`) | AC1.2.8 |
| [ ] Motivos | Cinco textos diferentes, nenhum só em cor; o ícone ⓘ com `aria-hidden` | AC1.2.7 |
| [ ] Carregando e erro | "Conferindo…" em `aria-busy`; o erro em `role="alert"` com o código | [Q3] |
| [ ] Diálogo | `dialog` nativo com `showModal()`, `role="alertdialog"`, `aria-modal`, `aria-labelledby` (título) e `aria-describedby` ("Este bot opera com dinheiro real.") | AC1.3.1 |
| [ ] Foco no diálogo | O primeiro foco vai para "Cancelar"; Tab circula dentro; Esc fecha sem efeito; ao fechar, o foco volta ao botão de origem | AC1.3.2 |
| [ ] Botão desabilitado | "Criar bot real" tem `aria-describedby` para "Marque a confirmação para criar o bot real.", sempre visível | AC1.3.2 |
| [ ] Contraste | Pares `text`/`surface`, `textMuted`/`surface`, `accent`/`surface`, `background`/`real` e `danger`/`surfaceRaised` (tabela acima) | AC4.4.1 |

### `/bots/new/{id}`: assistente (M3)

| Item | Critério | Fonte |
|---|---|---|
| [ ] Modo só em texto | "Modo: Real, em SOL · não pode ser trocado depois da criação" é texto, sem controle; o leitor de tela lê o modo | AC1.4.3 |
| [ ] Indicador de etapa | `ol` com `aria-current="step"` na etapa atual ("Etapa 2 de 5") | — |
| [ ] Botão de início | "Iniciar bot simulado" ou "Iniciar bot real"; o selo do REAL fica junto do texto | US1.4 |
| [ ] Limites REAL (M3b) | `dl` só leitura; cada valor lido com a unidade ("0,25 SOL, 10% do teto"); "não definido" em texto, nunca zero; carregando em `role="status"`; erro em `role="alert"` com o código; "Continuar" desabilitado com o motivo visível; ordem: "Alterar limites", "Voltar", "Continuar" | decisão D1 |

### `/bots`: lista (M4)

| Item | Critério | Fonte |
|---|---|---|
| [ ] Estrutura | h1 "Meus bots"; regiões header, nav e main; `fieldset` e `legend` "Modo" no filtro | wireframes, tela 1 |
| [ ] Foco inicial | No rádio marcado do filtro de modo | AC2.3.5 |
| [ ] Ordem do teclado | Filtro de modo (setas) → filtro de estado → cada linha: "Abrir" → "Pausar"/"Retomar" → próxima linha; "+ Novo bot" fica antes do filtro, com Shift+Tab | AC2.3.5 |
| [ ] Modo em texto | Cada linha tem `SIMULADO` ou `REAL` em texto no selo | AC2.1.2 |
| [ ] Tabela | `caption` "Meus bots" (com `visually-hidden`); `th scope="col"` em Nome, Modo, Estado, P&L e Ações; abaixo de 768 px, cartões com rótulos visíveis | — |
| [ ] Nome dos botões | "Abrir Momentum A, real" e "Pausar o bot Momentum A, real" (o nome com o modo, para distinguir bots de mesmo nome) | AC2.1.1 |
| [ ] P&L com moeda | Lido como "+0,0312 SOL, ganho" ou "−US$ 3,10, perda" | AC2.2.1 a AC2.2.3 |
| [ ] Contagem do filtro | "Mostrando N de M bots." em `aria-live="polite"` | AC2.3.2 |
| [ ] Avisos | O aviso do simulado e o aviso do US6.2 em `role="note"`, lidos como texto | AC4.2.3, AC6.2.3 |
| [ ] Estados | Carregando (`role="status"`), erro (`role="alert"` com o código), vazio (frase e ação) e `unknown` ("desconhecido") diferentes entre si | AC2.1.4 |
| [ ] Contraste | `text`/`background`, `textMuted`/`surface`, `accent`/`surface`, `danger`/`surface` e `unknown`/`surface` | AC4.4.1 |
| [ ] 360 px | Cartões empilhados, sem rolagem lateral | AC4.5.1 |

### `/pnl`: resultado (M5) e comparação (M5.7)

| Item | Critério | Fonte |
|---|---|---|
| [ ] Estrutura | Um h1 "Resultado"; h2 "Simulado, em dólares" e h2 "Real, em SOL"; h2 "Comparação" quando aberta, com h3 por lado; h2 da conferência e do ledger PAPER | AC4.4.5 |
| [ ] Foco inicial | No h2 "Simulado, em dólares" do primeiro bloco (`tabIndex={-1}`), e não no h1 como hoje; o período fica antes, com Shift+Tab | AC4.4.4, wireframes, tela 2 |
| [ ] Ordem do teclado | (Período: rádios e, com "Intervalo", "De", "Até" e "Aplicar") → bloco Simulado (links dos bots, "Ver por token", "Tentar de novo") → bloco Real (links dos bots, botões do `ProratedLines`, "Tentar de novo") → "Comparar…" → conferência → ledger | AC4.4.4 |
| [ ] Total com modo e moeda | "Total simulado, em dólares: +US$ 9,30, ganho" e "Total real, em SOL: +0,0312 SOL, ganho" | AC4.4.3 |
| [ ] `aria-live` | Cada total em `polite` e `aria-atomic`; anuncia a troca de período e a volta do preço; não anuncia a carga inicial | [Q5], AC3.8.4 |
| [ ] Período | `fieldset` e `legend` "Período"; "Mostrando: …" em `aria-live`; erro de intervalo com `aria-invalid` e `aria-describedby` no campo "Até"; o foco vai para o campo com erro | AC7.1.4 |
| [ ] Linhas rateadas | `button` com `aria-expanded` e `aria-controls`; nome "Ver 1 linha rateada de Momentum A"; a origem é lida em texto | AC3.3.3, AC3.4.3 |
| [ ] "Sem bot" | Lida com o motivo ("Sem bot, +0,0050 SOL, motivo: excedente sem dono"); não é link | AC3.5.2 |
| [ ] Estados por bloco | Cada bloco com `aria-busy` ao carregar; o erro com `role="alert"`, o código e "Tentar de novo" só do bloco; depois de "Tentar de novo", o foco vai para o h2 do bloco | [Q3], AC4.5.3 |
| [ ] `unknown` | "desconhecido (sem preço de XYZ)" e "Total: desconhecido (depende do não realizado)"; o realizado e as taxas visíveis; o aviso "◐ Dado parcial:" com símbolo e rótulo diferentes do aviso do simulado | AC3.8.2, AC3.8.3 |
| [ ] Aviso do simulado | Texto no bloco Simulado e no lado simulado da comparação; nunca no bloco Real | AC4.2.1, AC4.2.5, AC4.2.6 |
| [ ] Comparação | `button` com `aria-expanded`; os `select` nativos com rótulo; ajuda em `aria-describedby`; "Bot real" desabilitado com o motivo visível | AC8.1.1 a AC8.1.4 |
| [ ] Contraste | Todos os pares da tabela acima | AC4.4.1 |
| [ ] 360 px | Os blocos viram cartões empilhados, Simulado e depois Real; o foco anda de cartão em cartão na ordem do DOM | AC4.5.1, AC4.5.2 |

### `/`: painel (M6)

| Item | Critério | Fonte |
|---|---|---|
| [ ] Estrutura | `section` "Resultados por modo" (`aria-labelledby`) com h3 "Resultado simulado" e h3 "Resultado real"; o período "desde o início" escrito no rótulo da região | wireframes, tela 3, decisão D2 |
| [ ] Rótulos | Cada total é lido como "Total simulado, em dólares: …" ou "Total real, em SOL: …" | AC4.4.3 |
| [ ] `aria-live` | `polite`, no máximo uma vez a cada 30 s por lado, mesmo com o canal ao vivo | [Q5] |
| [ ] Links | "Ver o resultado simulado" e "Ver o resultado real" levam a `/pnl`; "Ativar a carteira do bot" e "Criar bot real" com texto próprio | AC4.3.3, AC4.3.4 |
| [ ] Ordem do teclado | O foco inicial fica no primeiro cartão, como hoje; então Simulado (link) → Real (link) → os demais cartões | AC4.4.4 |
| [ ] Estados | Cada lado independente: carregando, erro com o código, vazio ("Sem bots reais.") e `unknown` | AC4.3.2, [Q3] |
| [ ] 360 px | Os dois lados ficam empilhados, Simulado e depois Real | AC4.5.1 |

### `/bots/{id}`: detalhe (M7)

| Item | Critério | Fonte |
|---|---|---|
| [ ] Estrutura | O h1 é o nome do bot, com o selo; h2 "P&L do bot"; h2 "Linhas do P&L" | wireframes, tela 4 |
| [ ] Tabela | `caption` "Linhas do P&L de <bot>"; `th scope="col"` em Data (seu fuso), Lado, Token, Resultado (SOL), Taxa (SOL) e Origem; a origem lida em texto | AC3.10.4 |
| [ ] Datas | No fuso da pessoa, com o texto da coluna dizendo "seu fuso" | AC7.2.2 |
| [ ] Celular | Cartões com rótulo visível em cada valor | AC4.5.1 |
| [ ] Não encontrado | "Bot não encontrado." em `role="alert"`, sem nenhum dado, e o link "Voltar para Meus bots" | AC3.10.3 |
| [ ] Modo só em texto | Sem controle de troca | AC1.4.3 |
| [ ] Aviso | No bot simulado, em `role="note"`; ausente no bot real | AC4.2.4, AC4.2.6 |
| [ ] "Carregar mais" | Depois de carregar, o foco vai para a primeira linha nova | — |

## Conferência no spec de ponta a ponta

O spec novo do `scripts/verify.sh` (AC4.1.5) também confere em `/pnl`:
- os dois h2 ("Simulado, em dólares" e "Real, em SOL");
- os rótulos dos totais com o modo e a moeda;
- nenhum texto "Total geral".

Os testes de componente conferem as classes e a ordem do DOM de cada faixa. A conferência de 360 px sem rolagem lateral precisa de um navegador; ver a pergunta OQ7 de `mockups.md`.

## Assumptions & Open Questions

**Suposições** [assumption]:
1. **Limite do `aria-live`.** O limite de um anúncio a cada 30 s por bloco é uma escolha de design para o canal ao vivo do painel não encher o leitor de tela. Ele não vem de um critério.
2. **Nome dos botões.** "Abrir" e "Pausar" levam o modo no nome acessível porque o AC2.1.1 tem dois bots com o mesmo nome ("Momentum A") em modos diferentes.

**Perguntas em aberto:** consolidadas na lista única do fim de `mockups.md` (OQ1 a OQ7), cada uma com o dono e a opção recomendada.
