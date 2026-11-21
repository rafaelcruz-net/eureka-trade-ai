# Visão de negócio — eureka-trader-ai

## Propósito

**MemeTrade Analytics** é um SaaS de análise e operação automatizada de tokens de memes na rede Solana, hoje em
**beta PAPER por convite**: toda operação é simulada com saldo virtual, nenhuma ordem vai para a rede e nenhum dinheiro
real é usado. A fonte do produto é o PRD v1.3 (`PRD_MemeTrade_Analytics_v1.3_SaaS.md`); o código implementa o núcleo
"descoberta → avaliação → PAPER → P&L reconciliável", mais isolamento entre contas, auditoria, administração e
privacidade (LGPD).

**Atualização de 2026-11-21 (varredura focada do intent `261121-paper-real-unico`)**: o intent
`261113-trading-real-bot` construiu o modo REAL depois da varredura anterior. Hoje um bot nasce PAPER ou REAL (campo
`market`, fixo desde a criação). O bot REAL opera com a carteira do bot da pessoa (chave no KMS, assinatura só no
TxSigner), executa pela Jupiter e registra tudo num ledger REAL próprio (`real_ledger_entry`), separado do ledger
PAPER. O REAL continua desligado por padrão (`memetrade.real.enabled`) e depende da liberação aprovada pelo fundador.
O parágrafo abaixo descreve o estado de 2026-11-13 e vale hoje só para as partes fora da área analisada (por exemplo
`ModeGuardFilter` e os termos, não relidos nesta execução). O estado atual do REAL nas áreas analisadas está em
[architecture.md](architecture.md#estado-atual-do-modo-real).

> Estado de 2026-11-13: o modo REAL existia só como valor proibido; o banco, a API, a interface e os termos de uso
> diziam que ele estava indisponível.

## Atores

| Ator | Como entra | O que faz |
|---|---|---|
| Convidado (`BETA_USER`) | Convite emitido pelo administrador + assinatura de mensagem pela carteira (Phantom ou Solflare) | Vê o scanner, cria e opera bots PAPER, acompanha P&L, ledger e reconciliação, envia feedback, exerce direitos de titular |
| Administrador (`ADMIN`) | Carteira listada em `ADMIN_WALLET_ADDRESSES` + assinatura, por um app separado | Emite e revoga convites, liga e desliga o kill switch, vê o estado do sistema, lê feedback e a trilha de auditoria, atende pedidos de titular e anonimiza contas |
| Sistema (`system`) | Jobs agendados (processos `api` e `worker`) | Coleta mercado, recalcula risco, roda o ciclo dos bots, marca patrimônio, reconcilia, expurga dados com prazo |

Não existe papel de cliente pagante, cobrança nem assinatura: o PRD prevê isso para G4.

## Funcionalidades de negócio

| Área | O que o código entrega | Módulos |
|---|---|---|
| Entrada e conta | Login por mensagem assinada (nonce de uso único, 5 min), sessão de 15 min com renovação por cookie, conta criada só por convite com aceite dos termos e do aviso de risco, tela Conta | `identity`, `web-app` |
| Termos e privacidade | Termos e política versionados, publicação da versão vigente sem login, reaceite obrigatório quando a versão muda, tabela de retenção, pedidos de titular (acesso, correção, exclusão, portabilidade), anonimização | `identity`, `privacy`, `web-app`, `admin-app` |
| Descoberta | Scanner com filtros, ordenação e páginas por cursor sobre duas fontes simuladas, com fonte, horário, frescor, divergência e `unknown` explícito | `marketdata`, `scanner` |
| Avaliação de risco | Indicador de 0 a 100 por pesos versionados, faixa e dica `NO_TRADE` quando indisponível; nunca chamado de "seguro" | `riskassessment` |
| Bots PAPER | Assistente de criação a partir de dois modelos (momentum + volume, rompimento de faixa), limites versionados, ativar, pausar, retomar, parar; até 3 bots ativos por pessoa; saldo virtual de 100 a 10.000 USDC | `bots`, `decision` |
| Barreira de risco | Toda ordem passa por limites por ordem, exposição por token e total, slippage, liquidez mínima, frescor, intervalo, duplicidade, saldo, perda diária e drawdown; perda diária e drawdown pausam o bot | `riskcontrol` |
| Execução e contabilidade | Preenchimento simulado com spread, impacto, taxa e teto de consumo de liquidez; ledger append-only com compensação; posições, P&L realizado e não realizado, resumo diário, pico de patrimônio, reconciliação a cada 5 min e sob demanda | `ledger`, `pnl` |
| Bots REAL (2026-11-21) | O mesmo assistente e os mesmos modelos; `market = REAL` escolhido na criação só pela API (a tela de criação não envia `market`), exige carteira do bot ATIVA; o ciclo decide sobre o saldo e as posições da carteira do bot (compartilhada por todos os bots REAL da pessoa) e grava a decisão na fila REAL; não financia nem escreve o ledger PAPER; parar o bot não vende as posições | `bots`, `web-bots` |
| Execução REAL (2026-11-21) | Ordem por decisão aprovada pela barreira REAL, cotação e swap pela Jupiter, três modos da pessoa (`SIGNED`, `AUTO_EXITS`, `AUTO_FULL`), sessão de assinatura no navegador no modo Assinada, envio e confirmação, ordem ambígua, "vender tudo e sacar" (liquidação) | `realexecution`, `web-real` |
| Saídas condicionais e fiscal (2026-11-21) | Stop, alvo ou OCO da API Trigger da Jupiter por posição da carteira do bot (não por bot), cancelamento antes de qualquer venda a mercado (`PositionExitGuard`), exportação fiscal CSV do ledger REAL | `realexits`, `web-real` |
| P&L REAL (2026-11-21) | Realizado, não realizado e taxas em lamports, por custo médio por mint, da PESSOA inteira (todos os bots REAL e a liquidação juntos), sem período e sem filtro por bot; cartão próprio no painel e na área REAL, nunca somado ao PAPER | `pnl`, `web-real`, `web-dashboard` |
| Painel | Canal ao vivo dos bots, P&L e avisos de dados | `bots` (canal), `web-app` |
| Administração | Kill switch com histórico e reconciliação, estado do sistema só com contagens, convites, feedback, trilha | `administration`, `audit`, `feedback`, `admin-app` |
| Auditoria | Evento na mesma transação da ação (outbox), trilha append-only por 12 meses, carteira só como HMAC | `audit` |

## Regras de negócio que o código impõe

As regras abaixo estão no código, nos testes ou no banco, não só em documentos:

- **REAL e PAPER separados**: cada linha de negócio PAPER carrega `mode`, e as tabelas PAPER só aceitam `PAPER`. Desde
  o intent `261113-trading-real-bot` o REAL tem tabelas próprias (`real_ledger_entry` com `CHECK (mode = 'REAL')`,
  `real_order`, `real_conditional_order`), nunca linhas REAL nas tabelas PAPER; o bot guarda o modo em `bot.market`
  (`PAPER` ou `REAL`), enquanto `bot.mode` continua fixo em `PAPER`. `decision.mode` aceita `REAL` (0021).
- **P&L por bot só no PAPER**: cada bot PAPER tem a sua conta, as suas posições e o seu custo médio; o P&L REAL é da
  carteira do bot da pessoa, por mint, sem divisão por bot (detalhe em
  [architecture.md](architecture.md#como-o-pl-é-calculado-hoje)).
- **`unknown` nunca é zero**: `MarketValue` é um tipo selado (`Known` ou `Unknown`); um indicador indisponível vira
  `NO_TRADE`; regras ArchUnit proíbem `BigDecimal.ZERO` em `bots` e `riskcontrol` e ponto flutuante nos valores de
  mercado, ledger e risco.
- **Idempotência comum**: toda operação que muda estado passa pelo `IdempotencyGuard` do módulo `identity`; a ordem
  PAPER é única por `(tenant, mode, chave)`.
- **Ledger append-only**: gatilhos recusam UPDATE, DELETE e TRUNCATE em `ledger_entry` e `fill` para todo login;
  correção só por lançamento `COMPENSATION`.
- **Tenant do servidor**: tenant e pessoa vêm do token (ou, no worker, da linha do bot); toda rota de dados de cliente
  tem teste negativo nomeado em `tenancy/route-registry.yml`, senão o build falha.
- **Instantes em UTC e relógio injetável**: ArchUnit proíbe `Instant.now()` e similares fora de `ClockConfig`.
- **Nada de chave privada**: o produto nunca recebe seed phrase nem chave; a assinatura de login é conferida com
  Ed25519 nativo do Java; geração e importação de chave são proibidas em produção.

## Glossário

| Termo | Significado no código |
|---|---|
| PAPER | Modo simulado; único valor de `mode` aceito nas tabelas PAPER; valor padrão de `bot.market` |
| REAL | Modo com fundos reais; desde 2026-11 um valor de `bot.market`, com tabelas, módulos e telas próprias, desligado por padrão (`memetrade.real.enabled`) |
| `market` (do bot) | Campo do bot (`PAPER` ou `REAL`) escolhido na criação e nunca alterado; é ele, não `mode`, que diz se o bot é REAL |
| Carteira do bot | Uma carteira Solana por pessoa, gerada no KMS, que todos os bots REAL da pessoa usam; o ledger REAL é por carteira (`bot_wallet_id`), sem `bot_id` |
| Lamport | Menor unidade do SOL (10^-9); valores REAL são `long` em lamports, quantidades de token em unidades base |
| Liquidação ("vender tudo e sacar") | Pedido da pessoa que cancela as ordens não enviadas, vende cada posição da carteira do bot (`real_order` com `origin = 'USER_LIQUIDATION'` e sem bot) e saca tudo para a carteira principal |
| Saída condicional | Stop, alvo ou OCO da API Trigger da Jupiter sobre a posição inteira de um mint da carteira do bot; a venda entra no ledger REAL com a referência `conditional:<id>`, sem `real_order` |
| Slot | Janela UTC de 5 minutos; o ciclo de cada bot roda uma vez por slot, 60 s após o início |
| Barreira (`OrderGate`) | Contrato C14: avaliação obrigatória antes de qualquer execução |
| Kill switch | Estado global ON/OFF que pausa todos os bots com o motivo `SYSTEM` |
| Política de simulação | Parâmetros versionados do preenchimento PAPER (v1: spread 0,5%, impacto 1 ponto por ponto consumido, taxa 0,3%, teto de 10% da liquidez) |
| Reconciliação | Comparação interna de ordens, lançamentos, posições e resumo diário de uma pessoa |
| Gates G0 a G4 | Portões do PRD; G2 (arquitetura de delegação e threat model) e G3 (auditoria, testes e rollout real restrito) liberam o REAL |
| C-xx | Restrições do intent original (por exemplo C-06: nenhuma fonte de mercado real antes do G0) |

## Contexto do intent `261113-trading-real-bot`

O intent pede trading REAL com modos "Assinada", "Automática nas saídas" (ordens condicionais da Jupiter) e
"Automática total" (carteira do bot gerada pelo sistema, chave no KMS, depósito até um teto, saque só para a carteira
principal), execução pela Jupiter, ledger REAL separado e reconciliado com a blockchain, limites por ordem e por dia,
kill switch e rollout em devnet antes da mainnet. As peças existentes que ele pode reaproveitar e os bloqueios que
precisa remover ou rediscutir estão em [architecture.md](architecture.md#pontos-de-extensão-e-bloqueios-para-o-trading-real)
e em [code-quality-assessment.md](code-quality-assessment.md#riscos-para-o-trading-real).

## Contexto do intent `261121-paper-real-unico`

O intent pede um único ambiente, na mainnet, onde a pessoa cria bots simulados (PAPER) e reais (REAL) lado a lado, com
P&L por bot nos dois modos e um total separado por modo, nunca somado (dólar no PAPER, SOL no REAL). O P&L REAL por bot
é calculado na leitura, por custo médio por bot; as vendas sem bot ("vender tudo e sacar" e saídas condicionais) são
rateadas entre os bots donos das quantidades vendidas; depois, o ciclo do bot REAL passa a vender só a própria
quantidade.

O que o código oferece e o que falta, nas áreas analisadas nesta execução:

| Pedido do intent | Hoje no código |
|---|---|
| Lista única de bots com o modo em texto | A API já devolve PAPER e REAL juntos em `GET /api/v1/bots`, com `market`; a interface se chama "Bots PAPER" e não mostra nem escolhe `market` |
| P&L PAPER por bot | Existe: `GET /api/v1/pnl?botId=&from=&to=` |
| P&L REAL por bot | Não existe: `GET /api/v1/real/pnl` é da pessoa inteira; a origem por bot está em `real_order.bot_id` |
| Total por modo | Existe em cartões separados no painel (`PnlCard` PAPER do dia em USDC, `RealPnlCard` REAL acumulado em SOL), sem período comum |
| Rateio das vendas sem bot | Não existe: a venda da liquidação não tem `bot_id`; a venda condicional nem entra no P&L REAL |
| Bot REAL vende só a própria quantidade | Não: o ciclo vende a posição inteira do mint na carteira do bot |

Os detalhes estão em [architecture.md](architecture.md#como-o-pl-é-calculado-hoje) e em
[code-quality-assessment.md](code-quality-assessment.md#riscos-para-o-intent-261121-paper-real-unico).
