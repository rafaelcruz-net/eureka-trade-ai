# Mockups refinados — paper-real-unico

Mockups de média fidelidade, uma seção por tela e por história. Cada tela tem o desenho em ASCII, a descrição da estrutura e todos os estados: carregando, vazio, erro com o id de correlação, parcial e `unknown` [Q3].

Os valores são ilustrativos. Eles vêm dos critérios de aceite das histórias e não prometem lucro. Nenhuma tela soma os dois modos: não existe "Total geral" em nenhum desenho [Q8 de practices] (FR4.1, AC4.1.2). Toda cor vem acompanhada de texto, sinal ou ícone [Q5] (AC4.4.2).

## Sources

- `refined-mockups-questions.md`, respostas Q1 a Q7, todas A, com o resumo confirmado. É a fonte que manda.
- `ideation/rough-mockups/wireframes.md` (telas 1 a 6) e `user-flow.md`.
- `inception/user-stories/stories.md`: US1.1 a US8.1 e os seus critérios (AC).
- `inception/requirements-analysis/requirements.md`: FR1 a FR8 e NFR1 a NFR8.
- `inception/practices-discovery/team-practices.md`.
- O código atual:
  - `apps/web/src/features/bots/`: `BotCreate`, `BotWizard`, `BotList`, `BotDetail`, `BotsShell`;
  - `apps/web/src/features/real/`: `RealBadge`, `ModalDialog`, `RealPnlCard`, `RealErrorNotice`, `ReleaseStatus`, `money.ts`, `messages.ts`;
  - `apps/web/src/features/dashboard/`: `DashboardScreen`, `PnlCard`, `CardError`;
  - `apps/web/src/features/pnl/`: `PnlScreen`, `period.ts`, `SignedValue`;
  - `apps/web/src/lib/theme/tokens.ts` e `apps/web/src/app/globals.css`.

## Convenções dos desenhos

- **Selos de modo.** `[◇ SIMULADO]` é o selo novo `SimulatedBadge`, com contorno e sem preenchimento. `[◆ REAL]` é o `RealBadge` que já existe, com preenchimento âmbar. A forma e o ícone diferem, além da cor [Q4].
- **Moeda.** O PAPER aparece sempre em US$ e o REAL sempre em SOL (FR2.2, AC2.2.3).
- **Formato dos números.**
  - Os desenhos seguem o formato dos critérios: "+US$ 12,40" e "+0,0312 SOL".
  - Os zeros à direita são cortados, sem arredondar nenhum dígito diferente de zero. Assim, 31.200.000 lamports viram "0,0312 SOL" exatos.
  - Ver a pergunta OQ1 no fim deste arquivo.
- **Sinais.** "+" para ganho e "−" para perda, sempre com a palavra "(ganho)" ou "(perda)" para o leitor de tela. As taxas aparecem como valor pago, com "−" no total.
- **Aviso do simulado.** `⚠ O resultado simulado não garante o resultado real.` Ele aparece em toda tela que mostra P&L simulado e some na tela que mostra só P&L real (FR4.2, AC4.2.1 a AC4.2.6).
- **Datas.** Datas no fuso da pessoa, no formato "dd/mm HH:MM" (FR7.2, AC7.2.2).
- **Erro.** O texto do erro, o botão "Tentar de novo" e a linha "Código para o suporte: <correlationId>", que é o padrão do `RealErrorNotice` [Q3].

---

## M1. Criar bot, etapa "Modo" (`/bots/new`) — US1.1, US1.2, US1.5

### Onde fica o passo

O passo "Modo" é a primeira etapa do assistente de criação [Q1]. Ele aparece em `/bots/new`, a tela que hoje é o `BotCreate` (a etapa 1 do fluxo F1). O motivo:
- o `market` vai no pedido que cria o rascunho;
- ele não muda depois da criação (FR1.4).

O indicador do assistente passa de "Etapa N de 4" para "Etapa N de 5":
1. Modo e nome (`/bots/new`);
2. Estratégia e tokens;
3. Limites e saldo;
4. Revisão;
5. Início.

Ver a suposição 1.

### M1.1 Padrão: "Real" disponível, "Simulado" selecionado (AC1.1.1, AC1.2.1, AC1.5.1)

```
+----------------------------------------------------------------------+
| Novo bot                                                             |
| Etapa 1 de 5: Modo e nome                                            |
|                                                                      |
| Modo do bot                                                          |
| +-------------------------------+  +-------------------------------+ |
| | (o) [◇ SIMULADO]  Simulado    |  | ( ) [◆ REAL]  Real            | |
| |     Opera com saldo virtual   |  |     Opera com SOL da carteira | |
| |     em US$. Nenhuma operação  |  |     do bot, dentro do teto.   | |
| |     usa dinheiro real.        |  |     Pode perder o valor       | |
| |     ✓ Selecionado             |  |     operado.                  | |
| +-------------------------------+  +-------------------------------+ |
|                                                                      |
| Estratégia modelo                                                    |
|  (o) Momentum por volume   ( ) Reversão                              |
|                                                                      |
| Nome do bot                                                          |
| [Momentum A                                  ]                       |
| De 1 a 60 caracteres.                                                |
|                                                                      |
|                                         [ Criar rascunho simulado ] |
+----------------------------------------------------------------------+
```

- **Estrutura.**
  - h1 "Novo bot";
  - um `fieldset` com o `legend` "Modo do bot" e os dois cartões `ModeStep` (rádios);
  - o `fieldset` "Estratégia modelo", que já existe;
  - o campo "Nome do bot", que já existe.
- **Botão de envio.** O texto depende do modo:
  - "Criar rascunho simulado" no Simulado;
  - "Continuar para a confirmação" no Real, que abre o M2.
- **Cartão selecionado.** Mostra a borda de 3 px em `accent` e o texto "✓ Selecionado". A cor nunca fica sozinha.
- **Sem troca de rede ou de endereço.** Os dois modos ficam no mesmo app, na mainnet (AC1.5.1).

### M1.2 "Real" selecionado (AC1.1.3, AC1.3.4)

```
| +-------------------------------+  +-------------------------------+ |
| | ( ) [◇ SIMULADO]  Simulado    |  | (o) [◆ REAL]  Real            | |
| |     ...                       |  |     ...                       | |
| |                               |  |     ✓ Selecionado             | |
| +-------------------------------+  +-------------------------------+ |
| ...                                                                  |
|                                     [ Continuar para a confirmação ] |
```

Com "Simulado", a confirmação do dinheiro real nunca aparece (AC1.3.4).

### M1.3 "Real" desabilitado: um texto por motivo (AC1.2.2 a AC1.2.7, AC1.2.9) [Q7]

```
| +-------------------------------+  +-------------------------------+ |
| | (o) [◇ SIMULADO]  Simulado    |  | ( ) [◆ REAL]  Real            | |
| |     Opera com saldo virtual   |  |     Indisponível              | |
| |     em US$. ...               |  |     ⓘ A carteira do bot não   | |
| |     ✓ Selecionado             |  |       está ativa.             | |
| |                               |  |     → Ativar a carteira do bot| |
| +-------------------------------+  +-------------------------------+ |
```

- O cartão "Real" fica com `aria-disabled="true"`, o texto "Indisponível" e o motivo embaixo, ligado por `aria-describedby`.
- O cartão continua focável, para o leitor de tela ler o motivo. A escolha não muda.
- "Simulado" continua disponível em todos os casos (AC1.2.9).

| Ordem | Motivo (estado do servidor) | Texto mostrado | Link |
|---|---|---|---|
| 1 | Kill switch do REAL acionado | "As operações reais estão suspensas pelo sistema no momento." | nenhum (AC1.2.6) |
| 2 | REAL desligado (`memetrade.real.enabled` falso) | "O modo real está desligado no momento." | nenhum (AC1.2.5) |
| 3 | Carteira do bot inativa | "A carteira do bot não está ativa." | "Ativar a carteira do bot" → `/real` (ativação) (AC1.2.2) |
| 4 | Liberação ausente | "Sua conta ainda não foi liberada para o modo real." | nenhum (AC1.2.3) |
| 5 | Liberação em quarentena de 24 h | "Sua liberação para o modo real está em quarentena e ainda não vale. Vale a partir de dd/mm HH:MM." | nenhum (AC1.2.4) |

- **Regra de precedência** [Q7]:
  - quando vale mais de um motivo, aparece só o mais forte, na ordem da tabela: kill switch, REAL desligado, carteira inativa, liberação ausente, quarentena;
  - o link, quando existe, leva à ação que resolve esse motivo;
  - kill switch, REAL desligado, liberação ausente e quarentena não têm ação da pessoa, então não têm link.
- **Textos.**
  - Os cinco textos são diferentes entre si e nenhum depende de cor (AC1.2.7).
  - A hora de fim da quarentena vem de `effectiveAt`, no fuso da pessoa, como no `ReleaseStatus` atual.
  - Ver a pergunta OQ2 no fim deste arquivo.
- **A regra também vale no servidor** (AC1.2.8). Se o estado mudar entre abrir a tela e enviar o pedido, a API recusa com 4xx. O tratamento é um só e está no M2.4: o diálogo fecha, a tela relê a elegibilidade, volta para "Simulado" e mostra o motivo novo.
- **Teclado na opção indisponível.** Os rádios são nativos e controlados (`checked` vem do estado). "Real" indisponível tem `aria-disabled="true"` e continua na ordem das setas, para o motivo ser alcançável. Quando a seta chega em "Real", a seleção não acontece: o `onChange` recusa a troca, o rádio volta a desmarcado, "Simulado" continua marcado, o foco fica em "Real" e o leitor de tela lê "Real, indisponível" com o motivo (`aria-describedby`). Uma região `aria-live="polite"` repete "Real indisponível: <motivo>". O clique e o Espaço fazem o mesmo.

### M1.4 Carregando a disponibilidade do Real [Q3]

```
| +-------------------------------+  +-------------------------------+ |
| | (o) [◇ SIMULADO]  Simulado    |  | ( ) [◆ REAL]  Real            | |
| |     ...                       |  |     Conferindo se o modo real | |
| |     ✓ Selecionado             |  |     está disponível…          | |
| +-------------------------------+  +-------------------------------+ |
```

"Simulado" já pode ser usado. "Real" fica com `aria-disabled` e `aria-busy="true"` até a resposta chegar.

### M1.5 Erro ao conferir o Real (parcial) [Q3]

```
| +-------------------------------+  +-------------------------------+ |
| | (o) [◇ SIMULADO]  Simulado    |  | ( ) [◆ REAL]  Real            | |
| |     ...                       |  |     Indisponível              | |
| |     ✓ Selecionado             |  |  ! Não foi possível conferir  | |
| |                               |  |    o modo real agora.         | |
| |                               |  |    Código para o suporte:     | |
| |                               |  |    3f2a-9c1e                  | |
| |                               |  |    [ Tentar de novo ]         | |
| +-------------------------------+  +-------------------------------+ |
```

A tela continua útil para criar um bot simulado. "Real" fica desabilitado até a conferência dar certo, porque é a opção segura.

### M1.6 Erro ao criar o rascunho

```
| ! Não foi possível criar o rascunho. Tente de novo.                  |
|   Código para o suporte: 7b10-44d2                                   |
```

- O erro de domínio da API (AC1.2.8) mostra o texto do motivo, igual ao da tabela, e não o texto genérico.
- O nome e a estratégia digitados continuam preenchidos.

---

## M2. Confirmação do bot real (`RealConfirmDialog`) — US1.3

### M2.1 Aberto, caixa desmarcada (AC1.3.1, AC1.3.2)

```
+--------------------------------------------------------------+
| Confirmar bot real                                  [◆ REAL] |
|                                                              |
| Este bot opera com dinheiro real.                            |
|                                                              |
| Nome         Momentum A                                      |
| Estratégia   Momentum por volume                             |
| Modo         REAL, em SOL, com a carteira do bot             |
|                                                              |
| [ ] Confirmo: este bot opera com dinheiro real e as ordens   |
|     dele usam o SOL da carteira do bot.                      |
|                                                              |
| Marque a confirmação para criar o bot real.                  |
|                                                              |
|                      [ Cancelar ]   [ Criar bot real ] (off) |
+--------------------------------------------------------------+
```

- **Componente.** É o `ModalDialog` (`<dialog>` nativo com `showModal()`) com `role="alertdialog"`.
- **Foco.** O foco começa em "Cancelar", como no `MoneyConfirmDialog`. Esc fecha sem efeito, e o foco volta ao botão "Continuar para a confirmação".
- **Botão "Criar bot real".** Fica desabilitado enquanto a caixa está desmarcada [Q2]. A frase "Marque a confirmação para criar o bot real." fica sempre visível nesse estado e é a descrição do botão, então a tela diz o que falta (AC1.3.2).
- **Sem "Teto atual".** O resumo não mostra o teto, porque nenhuma história pede esse dado aqui. A exceção é quando os limites REAL do M3b já foram lidos na tela `/bots/new` (a mesma leitura): aí aparece a linha "Teto da conta: 2,5 SOL", ou "não definido". Sem essa leitura, a linha não aparece e o diálogo não faz um pedido só para ela (decisão do responsável, 2026-11-21).
- **Textos.** Nenhum texto promete lucro.

### M2.2 Caixa marcada

```
| [x] Confirmo: este bot opera com dinheiro real e as ordens   |
|     dele usam o SOL da carteira do bot.                      |
|                                                              |
|                      [ Cancelar ]   [ Criar bot real ]       |
```

### M2.3 Enviando (AC1.3.3, AC1.3.5)

```
|                      [ Cancelar ] (off) [ Criando o bot real… ] (off) |
```

- O primeiro clique desabilita os dois botões.
- O pedido leva uma `Idempotency-Key` criada quando o diálogo abre. Um segundo clique ou uma repetição usa a mesma chave, e só um bot nasce (AC1.3.5).
- Com sucesso, o diálogo fecha e a tela vai para a etapa 2 do assistente.

### M2.4 Erro no envio: recusa de domínio e erro técnico (AC1.2.8, AC1.3.5)

Há uma regra só para a recusa 4xx da criação REAL, a mesma do M1.3:

**Recusa de domínio (4xx com código, por exemplo a liberação perdida ou o kill switch acionado).** Repetir não muda o resultado, então:
1. o diálogo fecha e nenhum bot é criado;
2. a tela relê a elegibilidade do REAL;
3. a escolha volta para "Simulado", e o cartão "Real" fica indisponível com o motivo novo, da tabela do M1.3;
4. aparece a mensagem abaixo, em `role="alert"`, e o foco vai para ela.

```
| ! O bot real não foi criado.                                         |
|   Sua conta ainda não foi liberada para o modo real.                 |
|   Código para o suporte: 51ac-0e7f                                   |
```

O diálogo não oferece "Tentar de novo" nesse caso.

**Erro técnico (5xx, tempo esgotado, sem conexão).** O diálogo continua aberto, e só aqui aparece "Tentar de novo", com a mesma `Idempotency-Key`:

```
| ! Não foi possível criar o bot real. Tente de novo.          |
|   Código para o suporte: 9d02-71b3                           |
|                      [ Cancelar ]   [ Tentar de novo ]       |
```

---

## M3. Assistente depois da criação (`/bots/new/{id}`): o modo só em texto — US1.4

```
+----------------------------------------------------------------------+
| Assistente do bot                                                    |
| Etapa 2 de 5: Estratégia e tokens                                    |
| Modo: [◆ REAL] Real · não pode ser trocado depois da criação         |
| ...                                                                  |
+----------------------------------------------------------------------+
```

- **Modo só para leitura.** O modo aparece em texto, sem controle de troca nem ação de "converter" (AC1.4.3). O mesmo vale no detalhe do bot (M7) e no diálogo "Editar limites".
- **Botão de início.** Na etapa 5 o texto depende do modo: "Iniciar bot simulado" ou "Iniciar bot real", este com o `RealBadge`.
- **Etapa "Limites e saldo".** No PAPER continua como hoje. No REAL ela muda: ver o M3b.

## M3b. Etapa 3 de um bot real: limites da conta REAL, só leitura — US1.3, US1.4

Decisão do responsável, 2026-11-21. Um bot REAL não tem saldo virtual. A etapa mostra os limites REAL da conta da pessoa, só para leitura e em SOL. As regras da estratégia (entrada e saída) ficam como no PAPER, na etapa 2.

### M3b.1 Limites carregados

```
+----------------------------------------------------------------------+
| Assistente do bot                                                    |
| Etapa 3 de 5: Limites                                                |
| Modo: [◆ REAL] Real · não pode ser trocado depois da criação         |
|                                                                      |
| Limites da sua conta REAL                                            |
| Valem para todos os seus bots reais. Este bot não tem saldo virtual: |
| ele opera com o SOL da carteira do bot.                              |
|                                                                      |
| Teto da carteira do bot           2,5 SOL                            |
| Tamanho máximo por ordem          0,25 SOL (10% do teto)             |
| Compras de bot por dia            até 20                             |
| Perda máxima do dia               0,5 SOL (20% do teto)              |
|                                                                      |
| → Alterar limites (área Carteira do bot)                             |
|                                                                      |
|                                   [ Voltar ]   [ Continuar ]         |
+----------------------------------------------------------------------+
```

- **De onde vêm os valores.** São as entradas da `RealRiskPolicy`:
  - o teto (BR5.1);
  - o tamanho da ordem (BR4.1, `orderMaxBps`);
  - as compras de bot aprovadas por dia (BR4.2, `dailyBuysMax`);
  - a perda do dia (BR4.3, `dailyLossMaxBps`).

  A tela lê esses valores pela API de preferências da carteira do bot ou por uma leitura própria desses limites; o Functional Design escolhe.
- **Valores em SOL.** O tamanho da ordem e a perda do dia são percentuais do teto na política. A API devolve os dois já em lamports, arredondados para baixo como no BR7.2, e a tela mostra o percentual entre parênteses. A tela não calcula nada.
- **Compras por dia.** É uma contagem, não um valor em SOL.
- **Só leitura.** Nenhum campo é editável aqui. O link "Alterar limites" leva à área REAL (`/real`, teto em "Teto de operação"). Ao voltar, a etapa relê os limites.
- **"Continuar"** salva a etapa sem enviar limites: o rascunho REAL não guarda saldo virtual nem limites próprios.
- **Leitor de tela.** É uma `dl` com `dt` e `dd`; cada valor é lido com a unidade ("0,25 SOL, 10% do teto").

### M3b.2 Estados

**Carregando:**
```
| Limites da sua conta REAL                                            |
| Carregando os limites da conta REAL…                                 |
| ░░░░░░░░░░░░░░░░░░   ░░░░░░░                                         |
| ░░░░░░░░░░░░░░░░░░   ░░░░░░░                                         |
|                                  [ Voltar ]   [ Continuar ] (off)    |
```
"Continuar" fica desabilitado até a leitura terminar, com o texto "Espere os limites carregarem.".

**Limite não definido ou desconhecido:**
```
| Teto da carteira do bot           não definido                       |
| Tamanho máximo por ordem          não definido (depende do teto)     |
| Compras de bot por dia            até 20                             |
| Perda máxima do dia               não definido (depende do teto)     |
| → Definir o teto (área Carteira do bot)                              |
```
- Um limite sem valor, ou que o servidor não sabe, aparece como "não definido", nunca zero.
- Sem teto, o tamanho da ordem e a perda do dia também ficam "não definido (depende do teto)".
- O link muda para "Definir o teto".
- "Continuar" continua disponível: o bot pode ser criado, e a política recusa as compras enquanto não houver teto (BR5.1).

**Erro:**
```
| ! Não foi possível carregar os limites da conta REAL.                |
|   Código para o suporte: 2c7e-90a4                                   |
|   [ Tentar de novo ]                                                 |
|                                  [ Voltar ]   [ Continuar ] (off)    |
```
"Continuar" fica desabilitado até a leitura dar certo, para a pessoa não seguir sem ver os limites.

**Vazio:** não se aplica. Os quatro limites sempre aparecem, com valor ou "não definido".

**Celular (360 a 767 px):** cada limite vira duas linhas (rótulo; valor). Os botões ficam empilhados, com "Continuar" embaixo.

---

## M4. Lista de bots (`/bots`) — US2.1, US2.2, US2.3, US4.2, US6.2

### M4.1 Computador, 768 px ou mais, filtro "Todos" (AC2.1.1, AC2.1.2, AC2.2.1 a AC2.2.4, AC2.3.1)

```
+--------------------------------------------------------------------------+
| Meus bots                                                 [ + Novo bot ] |
| 2 de 3 bots ativos                                                       |
|                                                                          |
| Modo:  (o) Todos   ( ) Simulado   ( ) Real     Estado: [Todos os est. v] |
|                                                                          |
| ⚠ O resultado simulado não garante o resultado real.                     |
|                                                                          |
| Nome          | Modo           | Estado  | P&L (na moeda do modo) |      |
|---------------|----------------|---------|------------------------|------|
| Momentum A    | [◇ SIMULADO]   | Ativo   | +US$ 12,40 (ganho)     | Abrir|
| Momentum A    | [◆ REAL]       | Ativo   | +0,0312 SOL (ganho)    | Abrir|
| Reversão B    | [◇ SIMULADO]   | Pausado | −US$ 3,10 (perda)      | Abrir|
|               |                | pausado por você                 |      |
+--------------------------------------------------------------------------+
```

- **Cabeçalho.**
  - O h1 é "Meus bots". O item do menu muda de "Bots PAPER" para o mesmo texto, como pede a regra atual "o h1 é igual ao item do menu".
  - A etiqueta "MODO PAPER (simulado)" sai do título. Ver a suposição 3.
- **Modo.** Cada linha mostra o modo em texto (`SIMULADO` ou `REAL`) dentro do selo (AC2.1.2).
- **P&L.** O P&L de cada linha sai na moeda do próprio modo (AC2.2.1 a AC2.2.3).
- **Ordem.** A lista não tem ordenação por P&L. A ordem é a da criação, do mais novo para o mais antigo, então nenhum ranking mistura os modos (AC2.1.5).
- **Aviso.** O aviso do simulado aparece quando a lista visível tem pelo menos um bot simulado (AC2.2.4, AC4.2.3).
- **Ações.** As ações rápidas que já existem ("Pausar" e "Retomar") continuam na coluna de ações, junto de "Abrir".
- **Foco.** Quando a tela abre, o foco vai para a opção marcada do filtro de modo (AC2.3.5).

### M4.2 Filtro "Simulado" e filtro "Real" (AC2.3.2, AC2.3.3, AC4.2.6)

```
| Modo:  ( ) Todos   (o) Simulado   ( ) Real                              |
| ⚠ O resultado simulado não garante o resultado real.                     |
| Momentum A    | [◇ SIMULADO]   | Ativo   | +US$ 12,40 (ganho)     | Abrir|
| Reversão B    | [◇ SIMULADO]   | Pausado | −US$ 3,10 (perda)      | Abrir|
| Mostrando 2 de 3 bots.                                                   |

| Modo:  ( ) Todos   ( ) Simulado   (o) Real                               |
| Momentum A    | [◆ REAL]       | Ativo   | +0,0312 SOL (ganho)    | Abrir|
| Mostrando 1 de 3 bots.                                                   |
```

- Com o filtro "Real", a tela mostra só P&L real, então o aviso do simulado não aparece (AC4.2.6).
- A linha "Mostrando N de M bots." fica numa região `aria-live="polite"` e anuncia o resultado do filtro.
- O filtro fica no endereço (`?modo=simulado` ou `?modo=real`). Voltar para a tela mantém o filtro.

### M4.3 Celular, 360 a 767 px: cartões empilhados [Q6] (AC4.5.1)

```
+--------------------------------+
| Meus bots         [+ Novo bot] |
| 2 de 3 bots ativos             |
| Modo                           |
| (o) Todos                      |
| ( ) Simulado                   |
| ( ) Real                       |
| ⚠ O resultado simulado não     |
|   garante o resultado real.    |
| +----------------------------+ |
| | Momentum A   [◇ SIMULADO]  | |
| | Ativo                      | |
| | P&L: +US$ 12,40 (ganho)    | |
| | [Abrir]  [Pausar]          | |
| +----------------------------+ |
| | Momentum A   [◆ REAL]      | |
| | Ativo                      | |
| | P&L: +0,0312 SOL (ganho)   | |
| | [Abrir]  [Pausar]          | |
| +----------------------------+ |
+--------------------------------+
```

Os nomes longos quebram a linha (`overflow-wrap: anywhere`), e não há rolagem lateral.

### M4.4 Estados

**Carregando** [Q3] (AC2.1.4):
```
| Carregando os bots…                                                      |
| ░░░░░░░░░░░ | ░░░░░░░░ | ░░░░░ | ░░░░░░░░░░░░                            |
| ░░░░░░░░░░░ | ░░░░░░░░ | ░░░░░ | ░░░░░░░░░░░░                            |
```
O esqueleto de três linhas leva o texto "Carregando os bots…" em `role="status"`. O filtro fica visível e pode ser usado.

**Vazio, nenhum bot** (AC2.1.3):
```
| Você ainda não tem bots.                                                 |
| [ + Novo bot ]                                                           |
```
O filtro some e o aviso do simulado não aparece.

**Vazio pelo filtro** (AC2.3.4):
```
| Modo:  ( ) Todos   ( ) Simulado   (o) Real                               |
| Você não tem bots reais. Mostrando 0 de 3 bots.                          |
| [ Ver todos os bots ]                                                    |
```
Com o filtro "Simulado" o texto é "Você não tem bots simulados.". As duas frases são diferentes da tela sem nenhum bot.

**Erro** [Q3] (AC2.1.4):
```
| ! Não foi possível carregar os bots agora.                               |
|   Código para o suporte: c41d-2b07                                       |
|   [ Tentar de novo ]                                                     |
```

**P&L `unknown` de um bot** (AC2.1.4, AC3.8.2):
```
| Momentum A    | [◆ REAL]       | Ativo   | desconhecido (sem preço)  |
```
- O valor aparece em itálico, com a cor `unknown` e a palavra "desconhecido". Nunca aparece zero.
- O motivo fica entre parênteses ("sem preço", "preço desatualizado" ou "sem liquidez").

**P&L de um bot ainda carregando ou com erro, com a lista carregada (parcial)**:
```
| Momentum A    | [◆ REAL]       | Ativo   | P&L indisponível agora     |
```
O P&L de uma linha tem estado próprio, e a lista continua usável. "Indisponível" é um texto diferente de "desconhecido": desconhecido é falta de preço, indisponível é falha da leitura.

**Rascunho**: a linha mostra "Rascunho", o modo e "—" no P&L, com o link "Retomar", como hoje.

### M4.5 Aviso da venda só da própria quantidade (US6.2: AC6.2.1 a AC6.2.3)

```
| ⓘ Cada bot real agora vende só a quantidade que ele mesmo comprou.       |
|   Antes, um bot podia vender a posição inteira do token na carteira      |
|   do bot.                                                                |
```

- **Quando aparece.** Com a mudança ligada e pelo menos um bot real na lista (AC6.2.1). Com a mudança desligada, o aviso não aparece (AC6.2.2).
- **Leitura.** É um `role="note"` em texto, lido sem depender de cor nem de ícone. O ícone fica com `aria-hidden` (AC6.2.3).
- **Entrega.** Só depois da Performance Validation do intent 261113.

---

## M5. Resultado (`/pnl`) — US3.1 a US3.9, US4.1, US4.2, US4.4, US4.5, US5.3, US7.1, US7.2, US8.1

### M5.1 Computador, 768 px ou mais: os dois blocos lado a lado (AC4.1.1, AC4.4.5, AC7.1.1)

```
+------------------------------------------------------------------------------+
| Resultado                                                                    |
| Período: (o) Desde o início ( ) Últimos 7 dias ( ) Últimos 30 dias           |
|          ( ) Intervalo                                                       |
| Mostrando: desde o início · datas no seu fuso (America/Sao_Paulo)            |
|                                                                              |
| +-----------------------------------+  +-----------------------------------+ |
| | Simulado, em dólares [◇ SIMULADO] |  | Real, em SOL [◆ REAL]             | |
| | Total: +US$ 9,30 (ganho)          |  | Total: +0,0312 SOL (ganho)        | |
| | Realizado       +US$ 7,00         |  | Realizado       +0,0200 SOL       | |
| | Não realizado   +US$ 2,30         |  | Não realizado   +0,0130 SOL       | |
| | Taxas            US$ 0,00         |  | Taxas           −0,0018 SOL       | |
| | Não realizado: posições abertas   |  | Não realizado: posições abertas   | |
| | agora (marcação às 14:05)         |  | agora (marcação às 14:05)         | |
| |-----------------------------------|  |-----------------------------------| |
| | Bots simulados                    |  | Bots reais                        | |
| | Momentum A  Ativo   +US$ 12,40    |  | Momentum A  Ativo  +0,0312 SOL    | |
| | Reversão B  Pausado −US$ 3,10     |  |   inclui +0,0050 SOL fechado por  | |
| |                                   |  |   liquidação                      | |
| |                                   |  |   [▸ Ver 1 linha rateada]         | |
| | ⚠ O resultado simulado não        |  |                                   | |
| |   garante o resultado real.       |  |                                   | |
| +-----------------------------------+  +-----------------------------------+ |
|            (nenhuma linha, campo ou texto soma os dois blocos)               |
|                                                                              |
| [▸ Comparar um bot simulado com um bot real]                                 |
|                                                                              |
| Conferência do ledger PAPER  [◇ SIMULADO]   (ReconciliationPanel atual)      |
| Ledger PAPER                 [◇ SIMULADO]   (LedgerList atual)               |
+------------------------------------------------------------------------------+
```

- **Títulos.** h1 "Resultado". Cada bloco é uma `section` com h2 "Simulado, em dólares" e h2 "Real, em SOL" (AC4.4.5).
- **Total.** O total de cada bloco tem o rótulo para leitor de tela com o modo e a moeda: "Total simulado, em dólares" e "Total real, em SOL". Ele fica numa região `aria-live="polite"` (AC4.4.3) [Q5].
- **Taxas.** As taxas são subtraídas no total: total = realizado + não realizado − taxas.
- **Linhas dos bots.**
  - Cada bot do modo aparece na lista do próprio bloco, com o estado em texto ("Ativo", "Pausado" ou "Removido") (AC3.9.2, AC3.9.3).
  - Cada nome é um link para o detalhe (M7).
  - De 768 a 1.199 px, cada linha mostra o nome, o estado e o total.
  - Com 1.200 px ou mais, a linha vira uma tabela com as colunas Bot, Estado, Realizado, Não realizado, Taxas e Total.
- **Linha "Sem bot".** Aparece só quando é diferente de zero, sempre com o motivo (M5.4).
- **Ordem dos blocos.** Simulado e depois Real, à esquerda e à direita. É a mesma ordem de leitura do celular.
- **Foco inicial.** Quando a tela abre, o foco vai para o h2 do bloco Simulado, e não para o h1 como hoje. O período fica antes, com Shift+Tab (AC4.4.4).
- **O que sai da tela atual.**
  - A etiqueta "MODO PAPER (simulado)" sai do h1.
  - O item do menu muda de "P&L PAPER" para "Resultado".
  - A conferência e o ledger, que já existem e são só do PAPER, ficam embaixo, cada um com o selo `SIMULADO`.
  - A tabela "Resultado por token" do PAPER fica dentro do bloco Simulado, num bloco que abre e fecha ("Ver por token"). Ver a suposição 4.

### M5.2 Celular, 360 a 767 px: cartões empilhados (AC4.5.1, AC4.5.2) [Q6]

```
+--------------------------------+
| Resultado                      |
| Período                        |
| (o) Desde o início             |
| ( ) Últimos 7 dias             |
| ( ) Últimos 30 dias            |
| ( ) Intervalo                  |
| +----------------------------+ |
| | Simulado, em dólares       | |
| | [◇ SIMULADO]               | |
| | Total                      | |
| | +US$ 9,30 (ganho)          | |
| | Realizado     +US$ 7,00    | |
| | Não realizado +US$ 2,30    | |
| | Taxas          US$ 0,00    | |
| | Momentum A                 | |
| |   Ativo · +US$ 12,40       | |
| | Reversão B                 | |
| |   Pausado · −US$ 3,10      | |
| | ⚠ O resultado simulado não | |
| |   garante o resultado real.| |
| +----------------------------+ |
| +----------------------------+ |
| | Real, em SOL   [◆ REAL]    | |
| | Total                      | |
| | +0,0312 SOL (ganho)        | |
| | Realizado    +0,0200 SOL   | |
| | Não realizado +0,0130 SOL  | |
| | Taxas        −0,0018 SOL   | |
| | Momentum A                 | |
| |   Ativo · +0,0312 SOL      | |
| |   inclui +0,0050 SOL       | |
| |   fechado por liquidação   | |
| |   [▸ Ver 1 linha rateada]  | |
| +----------------------------+ |
+--------------------------------+
```

- Os cartões ficam empilhados, Simulado e depois Real.
- O valor do total fica numa linha própria, para não ser cortado.
- O foco do teclado anda de cartão em cartão, pelos elementos ativos de cada um.

### M5.3 Linhas rateadas abertas (`ProratedLines`) (AC3.3.3, AC3.4.3, AC3.5.1) [Q2]

```
| | Momentum A  Ativo  +0,0312 SOL    |
| |   inclui +0,0050 SOL fechado por  |
| |   liquidação                      |
| |   [▾ Ocultar 1 linha rateada]     |
| |   +-----------------------------+ |
| |   | 21/11 09:10 · XYZ           | |
| |   | Fechado por liquidação      | |
| |   | Recebido  0,3000 SOL        | |
| |   | Taxa     −0,0000025 SOL     | |
| |   | Realizado +0,0050 SOL       | |
| |   +-----------------------------+ |
```

- **Botão.** "Ver N linhas rateadas" é um `button` com `aria-expanded` e `aria-controls`. A lista fica escondida até abrir.
- **Linha de resumo.** Mostra a soma do realizado das parcelas de cada origem. Exemplos:
  - "inclui +0,0050 SOL fechado por liquidação";
  - "inclui −0,0010 SOL fechado por condicional";
  - "inclui +0,1000 SOL vendido por outro bot".
- **Origem em texto.** Cada linha aberta mostra a origem em texto ("Fechado por liquidação", "Fechado por condicional" ou "Vendido por outro bot") (AC3.10.2).
- **Bot sem parcela.** Um bot sem parcela rateada não mostra o botão nem a linha "inclui" (AC3.3.4, AC3.5.3).
- **Parcelas de uma condicional executada em parte.** Aparecem só as parcelas da parte executada (AC3.4.4).

### M5.4 Variação com bot removido e linha "Sem bot" (AC3.5.2, AC3.9.1, AC3.9.2, AC5.3.1)

```
| +-----------------------------------+ |
| | Real, em SOL [◆ REAL]             | |
| | Total: +0,0312 SOL (ganho)        | |
| | ...                               | |
| | Bots reais                        | |
| | Momentum A   Ativo     +0,0212 SOL| |
| | Rompimento C Removido  +0,0050 SOL| |
| |   inclui +0,0050 SOL fechado por  | |
| |   liquidação                      | |
| | Sem bot                +0,0050 SOL| |
| |   Motivo: excedente sem dono      | |
| +-----------------------------------+ |
```

- **Soma do bloco.** Os bots mais "Sem bot" somam o total do bloco: 0,0212 + 0,0050 + 0,0050 = 0,0312. A tela não mostra essa conta, mas os números fecham, porque a fonte é a mesma conferida com o ledger (AC5.3.1).
- **"Sem bot".** Não é um link e fica sempre com o motivo em texto. Uma linha "Sem bot" diferente de zero sem motivo não pode aparecer, porque a conferência falha antes (AC5.3.4).
- **Bot removido.** Continua na lista com o estado "Removido" e o P&L dele (AC3.9.2). O link leva ao detalhe só para leitura.

### M5.5 Período "Intervalo" (AC7.1.2 a AC7.1.5, AC7.2.1) [Q7]

```
| Período: ( ) Desde o início ( ) Últimos 7 dias ( ) Últimos 30 dias       |
|          (o) Intervalo                                                   |
|          De [10/11/2026]   Até (inclusive) [16/11/2026]  [ Aplicar ]     |
|          Datas no seu fuso (America/Sao_Paulo).                          |
| Mostrando: de 10/11 00:00 a 16/11 23:59 · realizado no período;          |
| não realizado de agora                                                   |
```

- **Opções.**
  - "Desde o início" é o padrão (AC7.1.1).
  - "Últimos 7 dias" vai de 00:00 de seis dias atrás até agora, no fuso da pessoa: são 7 dias inteiros contando hoje.
  - "Últimos 30 dias" segue a mesma regra, com 30 dias [Q7].
  - "Intervalo" inclui o dia inicial e o dia final inteiros [Q7].
- **Quando aplica.** As três opções fixas valem assim que são escolhidas. O intervalo só vale com "Aplicar".
- **Os dois blocos.** O período vale para os dois blocos, que continuam separados, sem total geral (AC7.1.5).
- **Não realizado.** É sempre o de agora (AC7.2.1). A linha "Mostrando: …" diz isso.

**Intervalo inválido** (AC7.1.4):
```
|          De [16/11/2026]   Até (inclusive) [10/11/2026]  [ Aplicar ]     |
|          ! A data final vem antes da inicial. Corrija o campo "Até".     |
```
- O campo "Até" fica com `aria-invalid="true"` e o erro é ligado a ele por `aria-describedby`.
- As datas digitadas são mantidas.
- Os blocos continuam com o último período válido.
- Um período de mais de 366 dias mostra "Escolha um período de até 366 dias.", o limite que já existe.

### M5.6 Estados de `/pnl` [Q3]

**Carregando** (AC4.5.3):
```
| +-----------------------------------+  +-----------------------------------+ |
| | Simulado, em dólares [◇ SIMULADO] |  | Real, em SOL [◆ REAL]             | |
| | Calculando o resultado simulado…  |  | Calculando o resultado real…      | |
| | ░░░░░░░░░░░░░░░                   |  | ░░░░░░░░░░░░░░░                   | |
| | ░░░░░░░░░░░                       |  | ░░░░░░░░░░░                       | |
| +-----------------------------------+  +-----------------------------------+ |
```
- Cada bloco carrega por conta própria, com `aria-busy="true"` e o texto em `role="status"`.
- O título e o selo aparecem desde o início. O valor nunca aparece como zero enquanto carrega.

**Parcial: um modo carregou e o outro deu erro** [Q3]:
```
| +-----------------------------------+  +-----------------------------------+ |
| | Simulado, em dólares [◇ SIMULADO] |  | Real, em SOL [◆ REAL]             | |
| | Total: +US$ 9,30 (ganho)          |  | ! Não foi possível carregar o     | |
| | ...                               |  |   resultado real.                 | |
| | ⚠ O resultado simulado não        |  |   Código para o suporte: e90b-11af| |
| |   garante o resultado real.       |  |   [ Tentar de novo ]              | |
| +-----------------------------------+  +-----------------------------------+ |
```
- "Tentar de novo" repete só a leitura daquele bloco.
- O outro bloco não muda e não é recalculado.

**Vazio por modo**:
```
| | Simulado, em dólares [◇ SIMULADO] |  | Real, em SOL [◆ REAL]             | |
| | Nenhuma operação simulada neste   |  | Sem bots reais.                   | |
| | período.                          |  | → Criar bot real                  | |
| | → Criar bot simulado              |  |                                   | |
```
- **Bloco Real, quando a carteira do bot está inativa:** "Sem bots reais." e o link "Ativar a carteira do bot".
- **Bloco Real, quando o Real está desabilitado por outro motivo:** "Sem bots reais." e o texto do motivo do M1.3, sem link de criação.
- **Pessoa sem nenhum bot nos dois modos:** a tela mostra "Você ainda não tem bots." e "+ Novo bot" no lugar dos dois blocos.
- **Aviso do simulado no bloco vazio:** não aparece, porque não há P&L simulado.

**`unknown`: preço desconhecido** (AC3.8.1 a AC3.8.3):
```
| | Real, em SOL [◆ REAL]             |
| | Total: desconhecido               |
| |   (depende do não realizado)      |
| | Realizado       +0,0200 SOL       |
| | Não realizado   desconhecido      |
| |                 (sem preço de XYZ)|
| | Taxas           −0,0018 SOL       |
| | ◐ Dado parcial: há posição sem    |
| |   preço confiável.                |
| | Momentum A  Ativo  desconhecido   |
| |   Realizado +0,0200 · Taxas       |
| |   −0,0018 · Não realizado         |
| |   desconhecido                    |
```
- O realizado e as taxas continuam exatos e visíveis. Só o que depende do não realizado aparece como "desconhecido", nunca como zero (AC3.8.3).
- O total de cada bot sem preço também fica desconhecido e mostra as partes conhecidas na linha de baixo.
- Quando o preço volta, a região do total anuncia o valor novo (AC3.8.4).
- **Aviso de dado parcial, diferente do aviso do simulado.** Usa o símbolo ◐ e o rótulo "Dado parcial:", com borda `unknown` (`.notice` com a classe nova `.notice-partial`). O aviso do simulado usa ⚠ e borda `warning`. Os dois nunca usam o mesmo símbolo nem o mesmo rótulo. Esse aviso troca o texto atual "Parcial: …" do `pnl.partial` e do `PnlCard`.

### M5.7 Comparação (`ComparisonView`), dentro de `/pnl` (US8.1: AC8.1.1 a AC8.1.4, AC4.2.5) [Q1]

```
| [▾ Comparar um bot simulado com um bot real]                              |
|   Bot simulado [Momentum A (Momentum por volume)   v]                      |
|   Bot real     [Momentum A (Momentum por volume)   v]                      |
|   Só aparecem bots reais da mesma estratégia do bot simulado escolhido.    |
|                                                                            |
|   +----------------------------------+  +----------------------------------+
|   | Momentum A [◇ SIMULADO]          |  | Momentum A [◆ REAL]              |
|   | Em dólares                       |  | Em SOL                           |
|   | Total          +US$ 12,40        |  | Total          +0,0312 SOL       |
|   | Realizado      +US$ 8,10         |  | Realizado      +0,0200 SOL       |
|   | Não realizado  +US$ 4,30         |  | Não realizado  +0,0130 SOL       |
|   | Taxas           US$ 0,00         |  | Taxas          −0,0018 SOL       |
|   | Estado         Ativo             |  | Estado         Ativo             |
|   | Criado em      01/11             |  | Criado em      15/11             |
|   | ⚠ O resultado simulado não       |  |                                  |
|   |   garante o resultado real.      |  |                                  |
|   +----------------------------------+  +----------------------------------+
|   Os valores ficam cada um na sua moeda e não são comparados por conta.    |
```

- **Os dois lados.** Cada lado mostra o próprio bot na própria moeda (AC8.1.1).
- **Nada comparado por conta.** Não há diferença calculada, percentual entre modos, ranking nem "vencedor" (AC8.1.2).
- **Aviso.** Fica só no lado simulado (AC8.1.3).
- **Endereço.** A vista abre e fecha no lugar. A escolha fica no endereço (`/pnl?comparar=<idPaper>,<idReal>`), e quem abre o link vê a comparação aberta.
- **Período.** É o mesmo do seletor de período.
- **Celular.** Abaixo de 768 px, os dois lados ficam empilhados, simulado e depois real.

**Estados da comparação**:
- **Vazio:** "Para comparar, você precisa de um bot simulado e de um bot real da mesma estratégia." e o link "+ Novo bot".
- **Carregando:** "Carregando a comparação…" em cada lado.
- **Erro em um lado:** o `RealErrorNotice` com o id de correlação e "Tentar de novo" só daquele lado. O outro lado continua.
- **Bot de outra pessoa ou inexistente pelo id** (AC8.1.4): "Bot não encontrado." no lado dele, sem nenhum dado, e a escolha volta para o seletor.
- **`unknown`:** igual ao M5.6.

---

## M6. Painel (`/`): os dois totais — US4.3, US4.2, US4.4

### M6.1 Os dois totais lado a lado (AC4.3.1, AC4.3.4, AC4.2.2)

```
+--------------------------------------------------------------------------+
| Painel                                                                   |
| Resultados por modo · desde o início                                     |
| +----------------------------------+  +--------------------------------+ |
| | Resultado simulado [◇ SIMULADO]  |  | Resultado real [◆ REAL]        | |
| | +US$ 9,30 (ganho)                |  | +0,0312 SOL (ganho)            | |
| | Realizado +US$ 7,00 · Taxas      |  | Realizado +0,0200 SOL · Taxas  | |
| |  US$ 0,00                        |  |  −0,0018 SOL                   | |
| | ⚠ O resultado simulado não       |  |                                | |
| |   garante o resultado real.      |  |                                | |
| | → Ver o resultado simulado       |  | → Ver o resultado real         | |
| +----------------------------------+  +--------------------------------+ |
|                                                                          |
| [Bots]  (BotsCard atual: todos os bots, com o selo de modo em cada um)   |
+--------------------------------------------------------------------------+
```

- **Região.** Os dois totais ficam numa `section` rotulada "Resultados por modo", cada um com o seu h3.
- **Valor e leitor de tela.** O valor é o que tem o rótulo "Total simulado, em dólares" e "Total real, em SOL" para o leitor de tela (AC4.4.3).
- **Links.** "Ver o resultado simulado" e "Ver o resultado real" levam a `/pnl` (AC4.3.4). O total inteiro não vira link, para o leitor de tela não ler o valor como texto de link.
- **Componentes que somem.** O cartão `PnlCard` ("P&L PAPER (simulado)", do dia UTC) e o `DashboardRealPnl` dão lugar a este par de `PnlModeBlock` na variante compacta.
- **Período: "desde o início"** (decisão do responsável, 2026-11-21). O período fica escrito na tela, na linha "Resultados por modo · desde o início", e vale para os dois lados. O painel deixa de mostrar o dia UTC ("Hoje") do `PnlCard` atual. O painel não tem seletor de período; outro período só no `/pnl`.

### M6.2 Lado Real sem bots e com a carteira inativa (AC4.3.2, AC4.3.3)

```
| | Resultado real [◆ REAL]        |      | | Resultado real [◆ REAL]        |
| | Sem bots reais.                |      | | Sem bots reais.                |
| | → Criar bot real               |      | | → Ativar a carteira do bot     |
|   (carteira ativa, sem bot REAL)        |   (carteira do bot inativa)      |
```

- O REAL desabilitado por outro motivo (kill switch, REAL desligado, liberação) mostra "Sem bots reais." e o texto do motivo, sem link de criação.
- O lado Simulado sem bots mostra "Sem bots simulados." e "→ Criar bot simulado", sem o aviso.

### M6.3 Estados do painel [Q3]

| Estado | Lado Simulado | Lado Real |
|---|---|---|
| Carregando | "Calculando o resultado simulado…" (`role="status"`) | "Calculando o resultado real…" |
| Erro (parcial) | "Não foi possível carregar o resultado simulado. Os outros quadros continuam funcionando." + código + "Tentar de novo" | o mesmo texto com "real" |
| `unknown` | "desconhecido (depende do não realizado)" + realizado e taxas | o mesmo |
| Vazio | "Sem bots simulados." | "Sem bots reais." |

Cada lado tem estado próprio. O erro de um lado nunca esconde o outro.

---

## M7. Detalhe do bot (`/bots/{id}`) — US3.10, US1.4, US4.2, US7.2

### M7.1 Bot real com a coluna de origem (AC3.10.1, AC3.10.2, AC3.10.4, AC7.2.2)

```
+--------------------------------------------------------------------------+
| Momentum A  [◆ REAL]                                                     |
| Modo: Real, em SOL · não pode ser trocado    Estado: Ativo   [Pausar]    |
|                                                                          |
| P&L do bot (desde o início)                                              |
| Total +0,0312 SOL (ganho)                                                |
| Realizado +0,0200 SOL · Não realizado +0,0130 SOL · Taxas −0,0018 SOL    |
|                                                                          |
| Linhas do P&L                                                            |
| Data (seu fuso) | Lado   | Token | Resultado        | Taxa (SOL)| Origem |
|-----------------|--------|-------|------------------|-----------|--------|
| 19/11 23:30     | Compra | XYZ   | —                | −0,000005 | Bot    |
| 20/11 15:40     | Venda  | XYZ   | +0,0150 SOL      | −0,000005 | Bot    |
| 21/11 09:10     | Venda  | XYZ   | +0,0050 SOL      | −0,0000025| Fechado|
|                 |        |       |                  |           | por    |
|                 |        |       |                  |           | liquid.|
+--------------------------------------------------------------------------+
```

- **Título e modo.** O h1 é o nome do bot, com o selo. O modo aparece só em texto (AC1.4.3).
- **Tabela.**
  - Tem `caption` "Linhas do P&L de Momentum A" e um `th scope="col"` por coluna (AC3.10.4).
  - A origem aparece em texto: "Bot", "Fechado por liquidação", "Fechado por condicional" ou "Vendido por outro bot" (AC3.10.2).
  - A abreviação "liquid." do desenho é só para caber no ASCII; na tela vai o texto inteiro, com quebra de linha.
- **Datas.** No fuso da pessoa: uma venda gravada em 2026-11-20T02:30Z aparece como 19/11 23:30 em America/Sao_Paulo (AC7.2.2).
- **Ordem.** A tabela vem da mais recente para a mais antiga e tem "Carregar mais", como `real.orders`.
- **Celular.** Abaixo de 768 px, cada linha vira um cartão com rótulos (`Data:`, `Lado:`…), como `real-orders-cards`.

### M7.2 Bot simulado (AC4.2.4, AC3.1.1)

```
| Momentum A  [◇ SIMULADO]                                                 |
| Modo: Simulado, em dólares · não pode ser trocado   Estado: Ativo        |
| ⚠ O resultado simulado não garante o resultado real.                     |
| P&L do bot: +US$ 12,40 (ganho) · Realizado +US$ 8,10 ·                   |
|   Não realizado +US$ 4,30 · Taxas US$ 0,00                               |
| (limites, uso do dia, operações simuladas, recusas e decisões: como hoje)|
```

As seções que já existem continuam iguais. O P&L do bot simulado não muda neste intent (AC3.1.1).

### M7.3 Estados do detalhe [Q3]

- **Carregando:** "Carregando o bot…" e "Carregando as linhas do P&L…", cada um no seu lugar.
- **Erro:** o texto, o código para o suporte e "Tentar de novo". O erro das linhas não esconde o cabeçalho nem o P&L.
- **Não encontrado ou de outra pessoa** (AC3.10.3): "Bot não encontrado.", sem nenhum dado e com o link "Voltar para Meus bots". A tela é a mesma para 404 e para inexistente.
- **Vazio:** "Nenhuma operação real até agora." ou "Nenhuma operação simulada até agora.".
- **`unknown`:** "Não realizado: desconhecido (sem preço de XYZ)" e "Total: desconhecido", com o realizado e as taxas exatos.
- **Bot removido:** um aviso "Este bot foi removido. O resultado dele continua aqui." Não há ações, e o P&L e as linhas continuam (AC3.9.2).
- **Bot pausado:** "Pausado", com o motivo em texto, como hoje (AC3.9.3).

---

## Mapa de cobertura das histórias

| História | Onde está | Observação |
|---|---|---|
| US1.1 | M1.1, M1.2 | — |
| US1.2 | M1.3 a M1.6 | precedência [Q7] |
| US1.3 | M2, M3b | M3b: limites REAL da etapa 3 (decisão D1) |
| US1.4 | M3, M3b, M7 | AC1.4.1 e AC1.4.2 são só da API |
| US1.5 | M1.1 (AC1.5.1) | AC1.5.2 e AC1.5.3 são de infraestrutura, sem tela |
| US2.1 | M4.1, M4.3, M4.4 | — |
| US2.2 | M4.1 | — |
| US2.3 | M4.2, M4.4 | — |
| US2.4 | — | só contrato (AC2.4.1 a AC2.4.4), sem tela |
| US3.1 | M5.1, M7.2 | — |
| US3.2 | M5.1, M7.1 | os valores vêm do cálculo; a tela não calcula |
| US3.3 | M5.3, M7.1 | marcação "Fechado por liquidação" |
| US3.4 | M5.3, M7.1 | marcação "Fechado por condicional" |
| US3.5 | M5.3, M5.4 | "Vendido por outro bot" e "Sem bot" |
| US3.6 | — | ordem do cálculo, sem tela própria |
| US3.7 | M7.1 (coluna Taxa) | a regra de quem paga é do cálculo |
| US3.8 | M4.4, M5.6, M7.3 | "desconhecido", nunca zero |
| US3.9 | M5.4, M7.3 | estados "Removido" e "Pausado" |
| US3.10 | M7.1 | — |
| US3.11 | M4.4, M5.6 (carregando) | a meta de 1 s é medida na carga, não na tela |
| US4.1 | M5.1, M6.1 | nunca somados |
| US4.2 | M4.1, M5.1, M6.1, M7.2, M5.7 | ausência em M4.2 (filtro Real) |
| US4.3 | M6 | — |
| US4.4 | todas (`accessibility-checklist.md`) | — |
| US4.5 | M4.3, M5.2, M5.6 | — |
| US5.1, US5.2 | — | correções do cálculo, sem tela |
| US5.3 | M5.4 | a linha "Sem bot" com o motivo; a conferência é do fundador |
| US5.4 | M5.5 | o realizado do período; a conferência é do cálculo |
| US6.1 | — | comportamento do ciclo, sem tela |
| US6.2 | M4.5 | depois da Performance Validation do intent 261113 |
| US7.1 | M5.5 | — |
| US7.2 | M5.5, M7.1 | — |
| US8.1 | M5.7 | — |

## Assumptions & Open Questions

**Suposições** [assumption]:
1. **Lugar do passo "Modo".** O [Q1] diz que o `BotWizard` ganha o passo "Modo". Como o `market` vai no pedido que cria o rascunho (`BotCreate`) e não muda depois (FR1.4), o passo fica em `/bots/new` como a etapa 1 de 5 do assistente. As etapas do `BotWizard` passam a ser de 2 a 5.
2. **Diálogo de confirmação na criação.** O diálogo abre antes de criar o rascunho real, e não no "Iniciar bot" da etapa 5, porque o FR1.3 diz "antes de criar um bot REAL".
3. **Etiqueta global "MODO PAPER (simulado)".** A etiqueta do cabeçalho do app e dos títulos de `/bots` e `/pnl` sai. Com os dois modos na mesma tela, ela ficaria errada. O modo passa a ser marcado em cada bot e em cada bloco. Os itens do menu passam a ser "Meus bots" e "Resultado".
4. **Partes do `/pnl` atual que continuam.** A conferência, o ledger e a tabela por token são do PAPER. Eles continuam, marcados com o selo `SIMULADO`, e não ganham equivalente REAL neste intent.
5. **Ordem da lista.** A lista vem na ordem de criação, do mais novo para o mais antigo. Ela não oferece ordenação por P&L (AC2.1.5).

## Decisões do responsável (2026-11-21)

- **D1. Etapa 3 de um bot real.** Sem saldo virtual. A etapa mostra os limites REAL da conta, só para leitura e em SOL (teto, tamanho da ordem, compras de bot por dia, perda do dia), com o link "Alterar limites" para a área REAL. As regras da estratégia ficam como no PAPER. Ver o M3b. Fecha a antiga pergunta em aberto 3 e o R-01 e o R-04 da revisão.
- **D2. Período do painel.** "Desde o início", escrito na tela. Ver o M6.1. Fecha a antiga pergunta em aberto 4 e o R-06 da revisão.

## Perguntas em aberto (lista única)

Esta é a única lista de perguntas em aberto dos quatro artefatos deste estágio. Os outros três arquivos apontam para cá. Nenhuma delas bloqueia o gate.

| ID | Pergunta | Dono | Opção recomendada |
|---|---|---|---|
| OQ1 | **Casas decimais e unidade.** Os critérios usam "+0,0312 SOL" e "+US$ 12,40". O `formatSignedSol` mostra sempre 9 casas (BR8.7 do intent 261113) e o PAPER mostra até 6 casas em "USDC". Também falta decidir se as telas do REAL que já existem (Visão geral, Depósito e saque, Ordens) mudam. | Functional Design | Cortar os zeros à direita sem arredondar, com pelo menos 2 casas (valor exato, igual aos critérios). Trocar "USDC" por "US$" nas telas do PAPER. As telas do REAL que já existem ficam com as 9 casas. |
| OQ2 | **Hora da quarentena.** O AC1.2.4 só tem o texto do motivo. | Functional Design | Acrescentar "Vale a partir de dd/mm HH:MM", com o `effectiveAt` que a tela de liberação já usa. |
| OQ3 | **Precedência dos motivos de "Real" desabilitado.** O servidor devolve só o motivo mais forte, ou a lista toda? | Functional Design | O servidor devolve só o mais forte, na ordem do [Q7]; a regra fica num lugar só, com teste de unidade. |
| OQ4 | **"Mesma estratégia" na comparação.** É o mesmo `templateId` ou também os mesmos parâmetros? | Functional Design | O mesmo `templateId`; com mais de um bot real, a pessoa escolhe na lista. |
| OQ5 | **`RealErrorNotice` nos erros do PAPER.** Para mostrar o código para o suporte no PAPER, o componente muda de lugar ou ganha um equivalente. | Functional Design | Mover para um lugar comum (por exemplo `components/`), depois de conferir a regra de lint de `features/real/`. |
| OQ6 | **Leitura dos limites REAL do M3b.** Vêm da API de preferências da carteira do bot ou de uma leitura própria das entradas da `RealRiskPolicy`? | Functional Design | Uma leitura que devolve os quatro limites já em lamports (arredondados para baixo, BR7.2) e o percentual, com `unknown` explícito. |
| OQ7 | **Conferência de 360 px sem rolagem lateral.** O jsdom não calcula layout. | NFR Requirements | Conferir no Playwright com a janela de 360 px, nas quatro telas, no spec novo do `verify.sh` (o responsável acrescenta). |
