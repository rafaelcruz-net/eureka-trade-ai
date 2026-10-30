# Wireframes iniciais — MemeTrade Analytics (beta PAPER por convite)

Esboços de baixa fidelidade para validar estrutura, hierarquia e estados das telas do beta. Não definem visual final, marca nem tecnologia. Os números e nomes nos esboços são ilustrativos.

## Base e decisões desta etapa

- O conteúdo das telas segue o MVP "Must mais enxuto" do [Scope Document](../scope-definition/scope-document.md): descoberta → avaliação → bot PAPER → P&L reconciliável, com acesso por convite, isolamento e auditoria como base.
- Cada tela corresponde a uma proto-Unit Must do [Intent Backlog](../scope-definition/intent-backlog.md): acesso (PB-01), scanner e avaliação (PB-02), bot PAPER (PB-03), ledger e P&L (PB-04). Filtros salvos, watchlist, alertas (PB-05) e métricas avançadas (PB-06) não aparecem.
- Público e limites vêm do [Intent Statement](../intent-capture/intent-statement.md): traders convidados no Brasil, familiarizados com Solana e carteiras; REAL e cobrança pública fora do beta.

| Decisão | Escolha | Origem |
|---|---|---|
| Primeira tela após o login | Painel com resumo do bot PAPER e do P&L, com atalho para o scanner | Q1 |
| Criação do bot PAPER | Assistente próprio em 4 etapas | Q2 |
| Idioma | Português; termos de mercado em inglês com explicação na tela | Q3 |
| Scanner | Tabela densa com detalhe do token em painel lateral | Q4 |
| Dispositivos | Apenas computador no beta | Q5 |
| Visual | Sem guia ainda; só tema escuro | Q6 |
| Acessibilidade | Boas práticas básicas no beta; WCAG 2.1 AA antes de abrir ao público | Q7 |

Os esboços abaixo usam só caracteres ASCII; por isso os textos dentro das caixas aparecem sem acentos. Na interface real os textos terão acentuação normal.

## Arquitetura de informação

```
[Convite e entrada] --> [Painel]
                           |
       +-------------------+-------------------+------------------+
       |                   |                   |                  |
   [Scanner]         [Bots PAPER]         [P&L PAPER]          [Conta]
       |                   |                   |
 [Painel lateral    [Assistente de bot]   [Ledger e
  do token]         [Acompanhamento]       reconciliacao]
```

<!-- Text fallback: Depois do convite e da entrada, o usuário chega ao Painel. Do menu principal saem cinco áreas: Painel, Scanner (com painel lateral do token), Bots PAPER (assistente de criação e acompanhamento), P&L PAPER (ledger e reconciliação) e Conta. -->

- Menu principal fixo no topo com cinco itens: Painel, Scanner, Bots PAPER, P&L PAPER, Conta. A carteira associada aparece no canto direito.
- Toda tela de bot e de P&L leva o rótulo persistente **MODO PAPER (simulado)** no título, com texto, nunca só por cor.
- Hierarquia rasa: qualquer tela fica a no máximo dois cliques do Painel.

## S1 — Convite, termos e entrada

Propósito: aceitar o convite, ler termos e aviso de risco e entrar assinando uma mensagem, sem que isso autorize operações.

```
+------------------------------------------------------------------------+
| MemeTrade Analytics  -  Beta por convite                               |
+------------------------------------------------------------------------+
|                                                                        |
|   Convite: [codigo preenchido pelo link]      (valido / expirado)      |
|                                                                        |
|   [ ] Li e aceito os Termos de Uso                 (ver termos)        |
|   [ ] Entendo o aviso de risco: sem promessa de lucro, sem             |
|       garantia de deteccao de fraude, beta apenas em modo PAPER        |
|                                                                        |
|   [ Conectar carteira ]   Phantom | Solflare                           |
|                                                                        |
|   Voce vai assinar uma mensagem so para entrar. Isso NAO autoriza      |
|   operacoes e nunca pedimos frase secreta ou chave privada.            |
+------------------------------------------------------------------------+
```

| Estado | O que a tela mostra |
|---|---|
| Vazio | Sem código de convite: campo para colar o código e explicação de que o beta é só por convite. |
| Carregando | Aguardando a carteira: "Confirme a assinatura na sua carteira", com opção de cancelar. |
| Erro | Convite expirado ou já usado; assinatura recusada ou expirada. Cada mensagem diz o que aconteceu e como tentar de novo. |
| Parcial | Termos não marcados: o botão de conectar fica indisponível, com o motivo escrito ao lado. |

Acessibilidade: título h1 com o nome do produto; regiões cabeçalho e conteúdo principal; o foco entra no campo de convite.

## S2 — Painel inicial

Propósito: mostrar ao convidado, ao entrar, o estado do bot PAPER, o P&L e a saúde dos dados, com atalhos para o scanner e para criar um bot.

```
+------------------------------------------------------------------------+
| [Menu] Painel | Scanner | Bots PAPER | P&L PAPER | Conta    [carteira] |
+------------------------------------------------------------------------+
| Painel                                              Dados: OK  13:42   |
|                                                                        |
| +-- Bot PAPER ----------------+  +-- P&L PAPER (simulado) ----------+  |
| | Ativos: 1   Pausados: 0     |  | Realizado: +0,42 SOL             |  |
| | Ultima operacao: 13:31      |  | Nao realizado: -0,05 SOL         |  |
| | [ Ver bot ]  [ Pausar ]     |  | Divergencias: 0   [ Ver P&L ]    |  |
| +-----------------------------+  +----------------------------------+  |
|                                                                        |
| Avisos: Fonte B sem atualizacao ha 6 min (frescor reduzido)            |
|                                                                        |
| [ Abrir scanner ]                    [ Criar bot PAPER ]               |
+------------------------------------------------------------------------+
```

| Estado | O que a tela mostra |
|---|---|
| Vazio | Primeiro acesso, sem bot: os quadros de bot e P&L dizem "Nenhum bot PAPER ainda" e oferecem "Abrir scanner" e "Criar bot PAPER". |
| Carregando | Quadros com marcadores de conteúdo enquanto os dados chegam. |
| Erro | Falha ao carregar o resumo: mensagem no quadro afetado, com "Tentar de novo"; o restante do painel continua visível. |
| Parcial | Uma fonte atrasada ou fora: aviso com nome da fonte e horário da última atualização; valores afetados marcados como desatualizados. |

Acessibilidade: título h1 "Painel"; regiões navegação, conteúdo principal e avisos; o foco entra no primeiro quadro. Avisos novos são anunciados a leitores de tela.

## S3 — Scanner com painel lateral do token

Propósito: descobrir tokens com filtros básicos e avaliar um token sem sair da lista, vendo fonte, horário, frescor e cobertura.

```
+----------------------------------------------------------------------------+
| [Menu] Painel | Scanner | Bots PAPER | P&L PAPER | Conta      [carteira]   |
+----------------------------------------------------------------------------+
| Scanner  Filtros: [idade v] [liquidez v] [volume v] [risco v] [limpar]     |
| Fontes: A ok 13:42 | B atrasada 13:36          Exibindo 1-50 de 312        |
| +------------------------------------------+ +-------------------------+   |
| | Token  Idade Liq.    Vol.1h Risco Fonte  | | Token XYZ (mint ...a9)  |   |
| | XYZ    12m   45k     120k   62    A      | | Avaliacao: NO_TRADE     |   |
| | ABC    40m   unknown 80k    --    A,B    | | Motivo: cobertura baixa |   |
| | DEF    3h    210k    1,1M   35    B      | | Liquidez 45k (A 13:42)  |   |
| | ...                                      | | Holders: unknown (B)    |   |
| |                                          | | Fatores e riscos [ver]  |   |
| | < Anterior   1 2 3 ...   Proxima >       | | [ Usar em bot PAPER ]   |   |
| +------------------------------------------+ +-------------------------+   |
+----------------------------------------------------------------------------+
```

Notas de interação:

- Selecionar uma linha abre o painel lateral; a lista mantém a posição e os filtros.
- Campo sem dado aparece como `unknown`, nunca como zero. Risco indisponível aparece como `--` com o motivo no painel (por exemplo, cobertura insuficiente).
- O painel mostra a avaliação (incluindo NO_TRADE), os fatores e riscos que a explicam, e a fonte e o horário de cada dado.
- Termos como "market cap", "holders" e "slippage" ficam em inglês, com explicação curta ao passar o mouse ou focar.
- "Usar em bot PAPER" leva ao assistente S4 com o token já selecionado na etapa de seleção.
- Os filtros disponíveis são um subconjunto inicial; quais entram no beta depende do discovery e do G0. Filtros salvos não fazem parte do beta.

| Estado | O que a tela mostra |
|---|---|
| Vazio | Nenhum token com os filtros atuais: mensagem explicando que os filtros eliminaram os resultados, com "Limpar filtros". |
| Carregando | Linhas com marcadores de conteúdo; o painel lateral mostra "Carregando avaliação". |
| Erro | Todas as fontes indisponíveis: aviso no topo, lista vazia com explicação e "Tentar de novo". |
| Parcial | Uma fonte atrasada: aviso com nome e horário; tokens só daquela fonte marcados como desatualizados. Token com poucos dados: avaliação NO_TRADE com o motivo. |

Acessibilidade: título h1 "Scanner"; regiões navegação, filtros, lista e painel de detalhe; o foco entra nos filtros. A lista navega por teclado, Enter abre o painel e Esc o fecha devolvendo o foco à linha. Risco alto também tem texto, não só cor.

## S4 — Assistente de criação do bot PAPER

Propósito: criar um bot PAPER em quatro etapas, com limites revisados antes de iniciar.

Etapas: 1 Seleção de tokens (regras de quais tokens o bot considera) → 2 Limites → 3 Revisão → 4 Início.

```
+----------------------------------------------------------------------------+
| Criar bot PAPER  -  MODO PAPER (simulado)              Etapa 2 de 4        |
+----------------------------------------------------------------------------+
| 1 Selecao de tokens  > 2 Limites  > 3 Revisao  > 4 Inicio                  |
|                                                                            |
| Tamanho maximo por ordem ......... [ 0,1 ] SOL                             |
| Exposicao maxima por token ....... [ 0,3 ] SOL                             |
| Exposicao maxima total ........... [ 1,0 ] SOL                             |
| Perda diaria maxima .............. [ 0,2 ] SOL                             |
| Slippage maximo .................. [ 3 ] %                                 |
| Liquidez minima .................. [ 20k ]                                 |
| Pausa entre operacoes ............ [ 5 ] min                               |
|                                                                            |
| A simulacao considera spread, slippage, taxas e liquidez (ver hipoteses)   |
|                                                                            |
| [ Voltar ]                                                [ Continuar ]    |
+----------------------------------------------------------------------------+
```

Notas de interação:

- Voltar entre etapas não perde o que foi preenchido.
- A etapa de revisão lista todos os limites e as hipóteses da simulação, e só então oferece "Iniciar bot PAPER".
- Valores fora do permitido são indicados no próprio campo, com a correção esperada, e impedem continuar.
- Nada nesta tela assina, envia ou autoriza operação real.

| Estado | O que a tela mostra |
|---|---|
| Vazio | Etapa 1 sem regra de seleção: orientação curta e exemplo; "Continuar" indisponível com o motivo escrito. |
| Carregando | Ao iniciar: "Iniciando bot PAPER", sem permitir duplo envio. |
| Erro | Limite inválido (por exemplo, exposição por token maior que a total): mensagem no campo. Falha ao iniciar: o bot fica em rascunho e a mensagem diz como tentar de novo. |
| Parcial | Assistente interrompido: o rascunho fica salvo e reaparece em Bots PAPER. |

Acessibilidade: título h1 "Criar bot PAPER" e h2 por etapa; regiões indicador de etapas e formulário; o foco entra no primeiro campo da etapa. Cada campo tem rótulo visível.

## S5 — Acompanhamento do bot PAPER

Propósito: ver o estado do bot, o uso dos limites e as operações simuladas, e pausar ou encerrar rapidamente.

```
+----------------------------------------------------------------------------+
| Bot PAPER  Momentum-01    MODO PAPER (simulado)        Estado: ATIVO       |
+----------------------------------------------------------------------------+
| Limites: ordem 0,1 | token 0,3 | total 1,0 | perda/dia 0,2 SOL             |
| Uso hoje: exposicao 0,6 de 1,0   perda 0,05 de 0,2                         |
|                                                                            |
| [ Pausar ]   [ Encerrar ]   [ Editar limites ]                             |
|                                                                            |
| Operacoes simuladas                                      (mais recentes)   |
| Hora   Token  Lado    Qtd   Preco sim.  Taxas  Slip.  ID operacao          |
| 13:31  XYZ    COMPRA  0,1   0,00042     0,001  1,2%   op-...7f2            |
| 13:05  DEF    VENDA   0,1   0,00310     0,001  0,8%   op-...1c9            |
|                                                                            |
| Ordens recusadas por limite: 2   [ ver motivos ]                           |
+----------------------------------------------------------------------------+
```

Notas de interação:

- "Pausar" impede novas operações simuladas imediatamente e mostra o estado PAUSADO.
- "Encerrar" pede confirmação dizendo o que acontece com as posições simuladas abertas.
- Cada operação simulada tem um identificador próprio e aparece uma única vez.
- Ordens recusadas por ultrapassar limites ficam visíveis, com o limite que as barrou.

| Estado | O que a tela mostra |
|---|---|
| Vazio | Bot ativo sem operações ainda: "Nenhuma operação simulada até agora" e as regras em uso. |
| Carregando | Lista de operações com marcadores de conteúdo. |
| Erro | Falha ao pausar ou encerrar: mensagem clara e nova tentativa; o estado mostrado é sempre o confirmado. |
| Parcial | Dados de mercado atrasados: aviso de que o bot pode ficar sem operar até os dados voltarem a ficar frescos. |

Acessibilidade: título h1 com o nome do bot; regiões resumo de limites, ações e lista de operações; o foco entra em "Pausar". Mudança de estado é anunciada a leitores de tela.

## S6 — P&L e ledger PAPER

Propósito: conferir resultados simulados por token e por bot, e reconciliar cada valor com as operações e as hipóteses da simulação.

```
+----------------------------------------------------------------------------+
| P&L PAPER  -  MODO PAPER (simulado)       Periodo: [hoje v]  [bot v]       |
+----------------------------------------------------------------------------+
| Realizado +0,42 | Nao realizado -0,05 | Taxas 0,012 | Simulado as 13:42    |
|                                                                            |
| Por token        Realizado   Nao real.  Taxas   Operacoes                  |
| XYZ              +0,30       -0,05      0,006   4                          |
| DEF              +0,12        0,00      0,006   2                          |
|                                                                            |
| Reconciliacao: 6 de 6 operacoes conferem com o ledger   [ detalhar ]       |
| Precos sem liquidez sinalizados: 1                                         |
|                                                                            |
| Ledger (eventos)                                   [ ver hipoteses ]       |
| 13:31 op-...7f2 COMPRA XYZ  custo 0,042  taxa 0,001  slip 1,2%             |
+----------------------------------------------------------------------------+
```

Notas de interação:

- Realizado, não realizado e taxas aparecem separados; ganho e perda usam sinal (+/-) e texto, não só cor.
- A linha de reconciliação mostra quantas operações conferem com o ledger; divergências aparecem destacadas, nunca escondidas.
- Preço sem liquidez é sinalizado ao lado do valor marcado.
- "Ver hipóteses" abre as premissas de spread, slippage, taxas e preenchimento usadas na simulação.

| Estado | O que a tela mostra |
|---|---|
| Vazio | Sem operações no período: explicação e atalho para o bot. |
| Carregando | Totais e tabela com marcadores de conteúdo. |
| Erro | Falha ao calcular: mensagem e nova tentativa; nenhum total parcial é exibido como completo. |
| Parcial | Divergência de reconciliação: aviso com quantas operações divergem e acesso ao detalhe. |

Acessibilidade: título h1 "P&L PAPER"; regiões filtros de período, totais, tabela por token e ledger; o foco entra no filtro de período. Tabelas com cabeçalhos de coluna.

## S7 — Conta

Propósito: ver a carteira associada, a sessão e os termos aceitos, e sair ou desconectar.

```
+----------------------------------------------------------------------------+
| [Menu] Painel | Scanner | Bots PAPER | P&L PAPER | Conta      [carteira]   |
+----------------------------------------------------------------------------+
| Conta                                                                      |
|                                                                            |
| Carteira associada: 7xK...p3Q (somente leitura)      [ Desconectar ]       |
| Sessao: expira em 11h                                [ Sair ]              |
| Termos e aviso de risco aceitos em 26/10/2026        [ ver ]               |
|                                                                            |
| Modo REAL: indisponivel no beta.                                           |
+----------------------------------------------------------------------------+
```

| Estado | O que a tela mostra |
|---|---|
| Vazio | Não se aplica: a conta sempre tem carteira associada após a entrada. |
| Carregando | Dados da conta com marcadores de conteúdo. |
| Erro | Sessão expirada: aviso e retorno à entrada, sem perder o rascunho de bot salvo. |
| Parcial | Desconectar carteira pede confirmação dizendo que bots PAPER ativos serão pausados. |

Acessibilidade: título h1 "Conta"; regiões navegação e conteúdo principal; o foco entra no primeiro botão.

## Diretrizes transversais do beta

- **Dispositivo:** layout para computador; telas pequenas não são atendidas no beta.
- **Tema:** apenas escuro. Ganho, perda, risco e estado do bot sempre combinam cor com texto ou ícone.
- **Acessibilidade no beta:** navegação por teclado nas telas principais, foco visível, rótulos visíveis em formulários e contraste legível no tema escuro. A conformidade completa com WCAG 2.1 AA fica como requisito antes de abrir ao público.
- **Dados:** toda informação de mercado mostra fonte e horário; ausente é `unknown`; dados atrasados são sinalizados.
- **Modo:** PAPER rotulado de forma persistente; nenhuma tela do beta oferece REAL.
- **Erros:** mensagens dizem o que aconteceu, por quê e o que fazer, sem códigos técnicos.

## Pontos em aberto para validar com convidados

- Quais colunas e filtros do scanner são mais úteis; a ordem dos critérios continua hipótese até o discovery.
- Se o painel inicial deve priorizar o bot, o P&L ou os avisos de dados.
- Quais regras de seleção de tokens fazem sentido na etapa 1 do assistente.
- Se o detalhe do token no painel lateral basta ou se falta espaço para os fatores da avaliação.
