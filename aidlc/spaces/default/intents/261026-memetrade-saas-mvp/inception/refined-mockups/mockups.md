# Mockups refinados — MemeTrade Analytics (beta PAPER por convite)

Fidelidade média: estrutura, conteúdo real, estados e comportamento por tela. A evolução parte dos [wireframes](../../ideation/rough-mockups/wireframes.md) e dos [fluxos](../../ideation/rough-mockups/user-flow.md) da Ideação, cobre as histórias de [stories.md](../user-stories/stories.md) e respeita [requirements.md](../requirements-analysis/requirements.md) e as práticas de [team-practices.md](../practices-discovery/team-practices.md) (contrato gerado, `unknown` como estado próprio, textos em pt-BR). Os números são ilustrativos. Os esboços usam só ASCII, por isso os textos dentro das caixas aparecem sem acento; na interface, os textos levam acentuação normal.

## Decisões desta etapa

| Decisão | Escolha | Origem |
|---|---|---|
| Base de componentes | Biblioteca de componentes acessíveis, sem visual imposto, com tokens próprios do tema escuro | Q1 |
| Largura mínima | 1280 px; abaixo, aviso de que o beta é só para computador | Q2 |
| Área administrativa | Endereço separado, com layout próprio | Q3 |
| Números | Alinhados à direita, algarismos de largura fixa, vírgula decimal, abreviações com valor exato ao passar o mouse ou focar | Q4 |
| Atualização do scanner | Valores atualizam no lugar; novos tokens e reordenação só com "Atualizar lista (N novidades)" | Q5 |
| Indicador de risco | Número + faixa escrita: Baixo (0–33), Médio (34–66), Alto (67–100), Indisponível | Q6 |

## Mapa de telas

| Tela | Nome | Histórias | Mudança em relação ao wireframe |
|---|---|---|---|
| S1 | Convite, termos e entrada | US1.1, US1.2 | Sem "desconectar"; aviso de instalação de carteira; estados de assinatura |
| S2 | Painel | US5.3, US5.4 | Quadro de bots com "Pausar"; aviso de atualização falha |
| S3 | Scanner e painel lateral | US2.1–US2.4, US3.1, US3.2 | Faixa de risco, "Atualizar lista", contador de excluídos por `unknown` |
| S4 | Assistente do bot (4 etapas) | US4.1–US4.3 | Etapa 1 renomeada "Estratégia e tokens"; revisão com contagem de ativos |
| S5 | Acompanhar bot | US4.5, US4.8, US4.10, US4.12 | Uso do dia com barras, decisões com frase simples |
| S6 | P&L e ledger | US5.1, US5.2 | `unknown` em não realizado, "reconciliado" com horário |
| S7 | Conta | US1.3, US1.5 | Só "Sair"; "Modo REAL indisponível" |
| S8 | Lista de bots | US4.4 | Nova |
| S9 | Feedback (diálogo) | US6.1 | Nova |
| A1 | Admin — estado do sistema e kill switch | US7.2, US7.4 | Nova, em endereço separado |
| A2 | Admin — convites | US7.1 | Nova |
| — | Aviso de largura insuficiente | NFR14, Q2 | Nova |

Navegação principal (S2 a S9): menu superior com Painel, Scanner, Bots PAPER, P&L PAPER, Conta, botão "Feedback" e a carteira abreviada. A área administrativa (A1, A2) tem endereço e menu próprios e não aparece no menu dos convidados.

## S1 — Convite, termos e entrada

Mantém o layout do wireframe S1. Refinamentos:

- "Conectar carteira" fica desabilitado com o motivo escrito ao lado ("Marque os dois aceites para continuar") até os dois aceites (AC1.1.1).
- Sem carteira instalada: texto com links para instalar Phantom ou Solflare e "Voltar", preservando os aceites (AC1.1.6).
- Aguardando assinatura: "Confirme a assinatura na sua carteira. Ela serve só para entrar e não autoriza operações." com "Cancelar".
- Assinatura recusada ou expirada: "Nada foi assinado. Tente de novo." com "Tentar de novo" (AC1.2.3).
- Convite inválido mostra o motivo exato: expirado, revogado ou já usado (AC1.1.2).

## S2 — Painel

Mantém o layout do wireframe S2. Refinamentos:

- Quadro "Bots PAPER": ativos e pausados, última operação, "Pausar" por bot sem confirmação (AC5.3.2).
- Quadro "P&L PAPER (simulado)": realizado, não realizado (com `unknown` parcial quando houver) e "Atualizado às HH:MM" (AC5.4.1).
- Faixa de avisos: fonte atrasada ou indisponível; falha da atualização automática ("Os valores podem estar desatualizados; última atualização às 13:40") (AC5.4.3).
- Falha em um quadro mostra a mensagem e "Tentar de novo" só nele (AC5.3.3).

## S3 — Scanner com painel lateral

```
+----------------------------------------------------------------------------------+
| Painel | Scanner | Bots PAPER | P&L PAPER | Conta  [?Feedback] [7xK..p3Q]        |
+----------------------------------------------------------------------------------+
| Scanner                           Atualizado as 13:42  [Atualizar lista (4)]     |
| Filtros: [Idade v] [Liquidez v] [Volume v] [Holders v] [Risco v] [Origem v] [x]  |
| Fontes: A ok 13:42 | B atrasada 13:36 (!)       3 excluidos por dado unknown     |
| +--------------------------------------------------+ +-------------------------+ |
| | Token  Idade Liq.(SOL) Vol.1h  Risco     Fonte   | | XYZ  mint 9fQ...a9  [x] | |
| | XYZ    12m      45,2k  120,0k  62 Medio  A       | | Risco 62 - Medio        | |
| | ABC    40m    unknown   80,1k  -- Indisp A,B     | | (em validacao; maior =  | |
| | DEF    3h      210,0k   1,10M  35 Medio  B (!)   | |  mais risco)            | |
| | GHI    6m       12,9k    9,8k  81 Alto   A       | | Avaliacao: NO_TRADE     | |
| |                                                  | | Motivo: holders unknown | |
| | Exibindo 1-50 de 312   < Anterior  Proxima >     | | Fatores (pesos v1.0):   | |
| |                                                  | |  Liquidez 45,2k A 13:42 | |
| |                                                  | |  Concentr. 38%  A 13:42 | |
| |                                                  | |  Holders unknown B      | |
| |                                                  | | [ Usar em bot PAPER ]   | |
| +--------------------------------------------------+ +-------------------------+ |
+----------------------------------------------------------------------------------+
```

- **Atualização (Q5):** os valores das linhas visíveis mudam no lugar e o horário "Atualizado às" acompanha. Novos tokens e mudanças de ordem ficam em espera; o botão "Atualizar lista (N)" mostra quantas novidades existem e, ao ser acionado, reordena mantendo o token selecionado e o foco (AC2.1.6).
- **Risco (Q6):** número e faixa escrita na mesma célula; `--` com "Indisp" quando a cobertura é insuficiente, e o motivo aparece ao passar o mouse ou focar (AC3.2.1).
- **`(!)` de fonte:** marca dado desatualizado (mais de 5 min) ou fonte atrasada, sempre com texto alternativo; divergência entre fontes usa o mesmo marcador com o texto "divergente" (AC2.3.1, AC2.4.1).
- **Contador de excluídos:** quantos tokens ficaram fora dos filtros por `unknown` no conjunto filtrado inteiro (AC2.2.2).
- **Estados:** carregando (linhas marcadoras), erro total (aviso no topo, lista vazia explicada, "Tentar de novo") e parcial (aviso por fonte) (AC2.3.2, AC2.3.3).

## S4 — Assistente do bot PAPER

### Etapa 1 — Estratégia e tokens

```
+----------------------------------------------------------------------------------+
| Criar bot PAPER - MODO PAPER (simulado)                          Etapa 1 de 4    |
+----------------------------------------------------------------------------------+
| (1) Estrategia e tokens > (2) Limites > (3) Revisao > (4) Inicio                 |
|                                                                                  |
| Estrategia modelo                                                                |
| ( ) Momentum de volume   - entra quando volume e preco sobem juntos              |
| (o) Rompimento de faixa  - entra quando o preco rompe a faixa recente            |
|     Janela da faixa .......... [ 30 ] min   (15 a 120)                           |
|     Alvo ...................... [ 8 ] %     (2 a 50)                             |
|     Stop ...................... [ 4 ] %     (1 a 30)                             |
|     Tempo maximo na posicao ... [ 60 ] min  (5 a 240)                            |
|                                                                                  |
| Quais tokens o bot considera                                                     |
|   Idade max. [ 60 min v]   Liquidez min. [ 20.000 SOL ]   Risco ate [ Medio v]   |
|   Tokens fixos: [ XYZ x ]  [+ adicionar do scanner]                              |
|                                                                                  |
| Rascunho salvo as 13:40                         [ Cancelar ]  [ Continuar > ]    |
+----------------------------------------------------------------------------------+
```

As faixas e os valores padrão são ilustrativos; os definitivos saem do design funcional (AC4.1.1, AC4.1.2). "Continuar" fica desabilitado, com o motivo, enquanto não houver regra de seleção (AC4.1.5). Cada etapa aceita salva o rascunho (AC4.1.3).

### Etapa 2 — Limites

Mantém o layout do wireframe S4, com: rótulo, unidade, valor padrão e frase simples por limite (por exemplo, "Perda diária máxima: o bot pausa sozinho ao perder este valor no dia, contado em UTC"), drawdown incluído e saldo virtual com o teto exibido (AC4.2.1 a AC4.2.4).

### Etapa 3 — Revisão

```
+----------------------------------------------------------------------------------+
| Criar bot PAPER - MODO PAPER (simulado)                          Etapa 3 de 4    |
+----------------------------------------------------------------------------------+
| (1) Estrategia e tokens > (2) Limites > (3) Revisao > (4) Inicio                 |
|                                                                                  |
| Revise antes de iniciar                                  Bots ativos: 2 de 3     |
| Estrategia ....... Rompimento de faixa (janela 30 min, alvo 8%, stop 4%)         |
| Tokens ........... idade <= 60 min, liquidez >= 20.000, risco ate Medio          |
| Saldo virtual .... 1,00 SOL (teto 10,00)                                         |
| Limites .......... ordem 0,10 | token 0,30 | total 1,00 | perda/dia 0,20         |
|                    drawdown 0,30 | slippage 3% | liq. min 20k | pausa 5 min      |
| Simulacao ........ spread, impacto, taxas e liquidez - versao sim-1.0 [ver]      |
|                                                                                  |
| Este bot nao assina nem envia transacoes reais.                                  |
|                                                                                  |
| [ < Voltar ]                                         [ Iniciar bot PAPER ]       |
+----------------------------------------------------------------------------------+
```

Com 3 bots ativos, a revisão mostra "3 de 3 bots ativos" e troca o botão por "Salvar rascunho", explicando que é preciso pausar ou encerrar um bot (AC4.3.2). Durante o envio: "Iniciando bot PAPER", sem novo envio (AC4.3.6).

### Etapa 4 — Início

Confirmação "Bot iniciado" com atalho para a tela do bot (S5).

## S5 — Acompanhar o bot

```
+----------------------------------------------------------------------------------+
| Painel | Scanner | Bots PAPER | P&L PAPER | Conta  [?Feedback] [7xK..p3Q]        |
+----------------------------------------------------------------------------------+
| Bot Rompimento-01  MODO PAPER (simulado)   Estado: ATIVO   [Pausar] [Encerrar]   |
|                                                                                  |
| Uso hoje (dia UTC; reinicia 21:00 Brasilia)                                      |
| Exposicao total  0,60 de 1,00 SOL   ######----  60%                              |
| Perda do dia     0,05 de 0,20 SOL   ##--------  25%                              |
| Drawdown         0,08 de 0,30 SOL   ###-------  27%                              |
|                                                                                  |
| Decisoes recentes                                            Atualizado 13:42    |
| 13:41 XYZ NO_TRADE  'Nao comprou XYZ: holders unknown'            [detalhe]      |
| 13:31 DEF BUY       'Comprou DEF: rompeu a faixa com liquidez'    [detalhe]      |
|                                                                                  |
| Operacoes simuladas            Ordens recusadas (2) [ver motivos]                |
| Hora   Token Lado   Qtd    Preco sim.   Taxa    Slip.  ID                        |
| 13:31  DEF   COMPRA 0,10   0,003100     0,0010  0,8%   op-1c9                    |
+----------------------------------------------------------------------------------+
```

- **Estados do bot em texto:** ATIVO, PAUSADO (com motivos acumulados: pessoa, perda diária, drawdown, sistema), ENCERRAMENTO PENDENTE, ENCERRADO. Com pausa, o botão vira "Retomar" e a tela avisa que posições abertas não serão fechadas enquanto o bot estiver pausado (AC4.8.4, AC4.8.5).
- **Barras de uso:** sempre com o valor em texto ao lado; acima de 80% o texto ganha o rótulo "perto do limite" (cor nunca sozinha).
- **Decisões:** a frase simples vem primeiro; "detalhe" abre fatores, dados de entrada com fonte e horário, versão da estratégia e validade (AC4.5.1).
- **Encerrar:** diálogo que lista posições e explica o encerramento pendente quando não houver preço confiável (AC4.12.1, AC4.12.3).

## S6 — P&L e ledger

Mantém o layout do wireframe S6. Refinamentos:

- Filtro de período no fuso do usuário; o rótulo lembra que o limite diário usa UTC (Q14).
- Não realizado de posição sem preço confiável aparece `unknown` com o motivo, e o total recebe o rótulo "parcial" (AC5.1.3).
- Linha de reconciliação: "Reconciliado às 13:42" ou o aviso de divergência com acesso ao detalhe (AC5.2.4).
- "Ver hipóteses" abre as premissas de spread, slippage, taxas e preenchimento com a versão da política (AC5.2.1).

## S7 — Conta

Mantém o layout do wireframe S7, sem "Desconectar" (Q13). "Sair" encerra a sessão e informa que os bots continuam rodando (AC1.3.2). "Modo REAL: indisponível no beta" em texto (AC1.5.3).

## S8 — Lista de bots

```
+----------------------------------------------------------------------------------+
| Painel | Scanner | Bots PAPER | P&L PAPER | Conta  [?Feedback] [7xK..p3Q]        |
+----------------------------------------------------------------------------------+
| Bots PAPER                                       Ativos 2 de 3   [ + Criar bot ] |
|                                                                                  |
| Nome            Estrategia         Estado                         Acoes          |
| Rompimento-01   Rompimento faixa   ATIVO                         [Abrir][Pausar] |
| Momentum-02     Momentum volume    PAUSADO - perda diaria         [Abrir]        |
|                                    volta as 21:00 (Brasilia)                     |
| Teste-03        Momentum volume    ENCERRAMENTO PENDENTE (1)      [Abrir]        |
| Rascunho-04     Rompimento faixa   RASCUNHO - etapa 2 de 4        [Retomar]      |
| Antigo-05       Momentum volume    ENCERRADO 20/10                [Abrir]        |
+----------------------------------------------------------------------------------+
```

Vazio: "Você ainda não tem bots PAPER" com "Criar bot". Só aparecem ações válidas para o estado (AC4.4.2).

## S9 — Feedback

```
+----------------------------------------------------------------------------------+
| Enviar feedback                                                       [x]        |
+----------------------------------------------------------------------------------+
| Tela de origem: Scanner (registrada automaticamente)                             |
|                                                                                  |
| O que voce quer contar?  (ate 2.000 caracteres)                                  |
| [                                                                           ]    |
| [                                                                           ]    |
|                                                                 0 / 2.000        |
|                                                                                  |
| Seu comentario fica visivel so para a administracao do beta.                     |
|                                                                                  |
|                                               [ Cancelar ]  [ Enviar ]           |
+----------------------------------------------------------------------------------+
```

O limite de 2.000 caracteres é um valor inicial proposto. Falha ao enviar preserva o texto (AC6.1.3).

## A1 e A2 — Área administrativa (endereço separado)

```
+----------------------------------------------------------------------------------+
| MemeTrade Admin   Estado | Convites | Kill switch | Feedback  [admin 4Fh..9zQ]   |
+----------------------------------------------------------------------------------+
| Estado do sistema                                             Atualizado 13:42   |
|                                                                                  |
| Kill switch: DESLIGADO                           [ Acionar kill switch ]         |
|                                                                                  |
| Convidados ativos ...... 7        Convites pendentes ...... 3                    |
| Bots ATIVOS ............ 11       Bots PAUSADOS ........... 4                    |
| Encerramento pendente .. 1        Ordens recusadas hoje ... 23                   |
|                                                                                  |
| Fontes de dados      Estado        Ultima atualizacao                            |
| Fonte A (simulada)   ok            13:42                                         |
| Fonte B (simulada)   atrasada (!)  13:36                                         |
|                                                                                  |
| Sem acesso a ledger, operacoes ou carteiras dos convidados.                      |
+----------------------------------------------------------------------------------+
```

```
+----------------------------------------------------------------------------------+
| MemeTrade Admin   Estado | Convites | Kill switch | Feedback  [admin 4Fh..9zQ]   |
+----------------------------------------------------------------------------------+
| Convites                                                   [ + Emitir convite ]  |
|                                                                                  |
| Codigo     Emitido em    Validade      Estado     Acoes                          |
| K7P-..-Q2  20/10 10:02   27/10 10:02   PENDENTE   [Copiar link][Revogar]         |
| M3T-..-X9  18/10 15:40   25/10 15:40   EXPIRADO   -                              |
| A1B-..-Z7  15/10 09:10   22/10 09:10   USADO      -                              |
| C9D-..-W4  14/10 11:00   21/10 11:00   REVOGADO   -                              |
+----------------------------------------------------------------------------------+
```

- Endereço próprio (Q3), com a mesma entrada por assinatura; quem não é administrador recebe "Acesso restrito" e o acesso é auditado (AC7.1.4, AC7.2.5).
- "Acionar kill switch" abre confirmação com o número de bots que serão pausados; ligado, o topo de todas as telas administrativas mostra "Kill switch ACIONADO" (AC7.2.1).
- Revogar convite pede confirmação (AC7.1.3). Feedback lista os comentários como texto puro, com tela de origem e horário (AC6.1.4).

## Aviso de largura insuficiente

```
+----------------------------------------------------------------------------------+
| MemeTrade Analytics                                                              |
+----------------------------------------------------------------------------------+
| O beta funciona em telas de computador com pelo menos 1280 px de largura.        |
| Amplie a janela ou use um computador para continuar.                             |
+----------------------------------------------------------------------------------+
```

Aparece abaixo de 1280 px sem perder o estado da tela; ao ampliar a janela, a tela volta como estava (Q2).
