# Perguntas dos Refined Mockups — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes: `ideation/rough-mockups/`, `inception/user-stories/stories.md` (US1 a US8), `inception/requirements-analysis/requirements.md` e os componentes que já existem em `apps/web/src/features/` (bots, real, dashboard) e `components/`.

## Q1. Como cada história aparece na tela

Onde cada grupo de histórias aparece na interface?

A. Nas telas que já existem, ajustadas:
   - **Criar bot:** o assistente `BotWizard` ganha o passo "Modo" (US1).
   - **Lista de bots:** `/bots` ganha a coluna "Modo" e o filtro (US2).
   - **P&L e totais:** `/pnl` ganha os blocos por modo, o período e as linhas rateadas (US3, US4 e US7).
   - **Painel:** mostra os dois totais (US4.3).
   - **Detalhe do bot:** `/bots/{id}` mostra o P&L e as linhas com a origem (US3.10).
   - **Comparação:** é uma vista dentro de `/pnl` (US8).

   (recomendado)
B. Telas novas para o P&L unificado
X. Other (please specify)

[Answer]: A

## Q2. Padrões de interação

Quais padrões de interação usar?

A. Os que já existem no produto:
   - **Escolha do modo:** um passo do assistente, com dois cartões selecionáveis ("Simulado" e "Real").
   - **Confirmação do real:** um `ModalDialog` com uma caixa de confirmação, e o botão só fica ativo quando a caixa está marcada.
   - **Motivo do "Real" desabilitado:** aparece em texto embaixo do cartão.
   - **Detalhe das linhas rateadas:** fica num bloco que abre e fecha na tela de P&L.

   Não entra nenhum padrão novo. (recomendado)
B. Uma tela de assistente separada para o real
X. Other (please specify)

[Answer]: A

## Q3. Estados de cada tela

Que estados cada tela precisa mostrar?

A. Todos os estados abaixo (recomendado):
   - **Carregando:** um esqueleto.
   - **Vazio:** "Você ainda não tem bots", com o botão "+ Novo bot".
   - **Erro:** o texto do erro, o botão "tentar de novo" e o id de correlação.
   - **Sucesso.**
   - **Parcial:** um modo carregou e o outro deu erro. Cada bloco fica independente.
   - **`unknown`:** o não realizado aparece como "desconhecido".
B. Só carregando, erro e sucesso
X. Other (please specify)

[Answer]: A

## Q4. Sistema de design

O que fazer com os componentes visuais?

A. Usar os componentes e os tokens que já existem:
   - o tema escuro;
   - o token `real`, que já existe;
   - o `RealBadge`, que é o selo do REAL;
   - um selo `SIMULADO` novo, no mesmo padrão do `RealBadge`;
   - o `ModalDialog`;
   - os cartões do REAL.

   O documento de mapeamento lista cada componente usado e o que é novo. (recomendado)
B. Um tema novo
X. Other (please specify)

[Answer]: A

## Q5. Acessibilidade

Que nível de acessibilidade seguir?

A. O nível do beta, com os limites dos requisitos:
   - contraste mínimo de 4,5:1;
   - teclado e foco visível;
   - rótulos para leitor de tela com o modo e a moeda;
   - `aria-live` para mudanças de total;
   - cor sempre com texto.

   O WCAG 2.1 AA completo fica para a abertura ao público. (recomendado)
B. WCAG 2.1 AA completo já
X. Other (please specify)

[Answer]: A

## Q6. Tamanhos de tela

Em que tamanhos de tela a interface precisa funcionar?

A. Em três faixas (recomendado):
   - **360 px a 767 px:** cartões empilhados, sem rolagem lateral;
   - **768 px a 1.199 px:** blocos lado a lado, com a lista em tabela;
   - **1.200 px ou mais:** largura máxima de leitura.
B. Só computador e celular
X. Other (please specify)

[Answer]: A

## Q7. Pontos deixados em aberto pelas histórias

Como fechar os pontos que as histórias deixaram em aberto?

A. Assim (recomendado):
   - **Dois motivos de "Real" desabilitado ao mesmo tempo:** aparece só o mais forte, nesta ordem: kill switch, REAL desligado, carteira inativa, liberação ausente, liberação em quarentena. O link vai para a ação que resolve esse motivo.
   - **Períodos de 7 e 30 dias:** contam dias inteiros no fuso da pessoa, de 00:00 do primeiro dia até agora.
   - **Intervalo escolhido:** inclui o dia inicial e o dia final inteiros.
B. Mostrar todos os motivos juntos
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Telas.** Nada de tela nova; as atuais são ajustadas:
  - o assistente de criação ganha o passo "Modo";
  - a lista ganha a coluna "Modo" e um filtro;
  - `/pnl` ganha os blocos por modo, o período, as linhas rateadas e a comparação;
  - o painel ganha os dois totais;
  - o detalhe do bot passa a mostrar a origem de cada linha [Q1].
- **Interação.** Usa só padrões que já existem:
  - cartões "Simulado" e "Real" para escolher o modo;
  - um modal com caixa de confirmação para o real;
  - o motivo em texto quando o "Real" está desabilitado;
  - um bloco que abre e fecha para as linhas rateadas [Q2].
- **Estados.** Carregando, vazio, erro (com "tentar de novo" e o id de correlação), sucesso, parcial (cada bloco independente) e `unknown` [Q3].
- **Visual.** Os componentes e o tema que já existem, mais um selo `SIMULADO` novo [Q4].
- **Acessibilidade.** 4,5:1, teclado, rótulos com o modo e a moeda, `aria-live` nos totais e cor sempre acompanhada de texto [Q5].
- **Tamanhos de tela.** 360 a 767 px empilhado, 768 a 1.199 px lado a lado e 1.200 px ou mais com largura máxima [Q6].
- **Pontos abertos.**
  - Quando há dois motivos, aparece só o mais forte, na ordem: kill switch, REAL desligado, carteira inativa, liberação ausente, quarentena.
  - Os períodos de 7 e 30 dias contam dias inteiros no fuso da pessoa.
  - O intervalo escolhido inclui o dia inicial e o final [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
