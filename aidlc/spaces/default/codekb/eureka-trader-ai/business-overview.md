# Visão de negócio — eureka-trader-ai

## Propósito

**MemeTrade Analytics** é um SaaS de análise e operação automatizada de tokens de memes na rede Solana, hoje em
**beta PAPER por convite**: toda operação é simulada com saldo virtual, nenhuma ordem vai para a rede e nenhum dinheiro
real é usado. A fonte do produto é o PRD v1.3 (`PRD_MemeTrade_Analytics_v1.3_SaaS.md`); o código implementa o núcleo
"descoberta → avaliação → PAPER → P&L reconciliável", mais isolamento entre contas, auditoria, administração e
privacidade (LGPD).

O modo REAL existe só como valor proibido: o banco, a API, a interface e os termos de uso dizem que ele está
indisponível (detalhes em [architecture.md](architecture.md#estado-atual-do-modo-real)).

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
| Painel | Canal ao vivo dos bots, P&L e avisos de dados | `bots` (canal), `web-app` |
| Administração | Kill switch com histórico e reconciliação, estado do sistema só com contagens, convites, feedback, trilha | `administration`, `audit`, `feedback`, `admin-app` |
| Auditoria | Evento na mesma transação da ação (outbox), trilha append-only por 12 meses, carteira só como HMAC | `audit` |

## Regras de negócio que o código impõe

As regras abaixo estão no código, nos testes ou no banco, não só em documentos:

- **REAL e PAPER separados**: cada linha de negócio carrega `mode`, e o banco só aceita `PAPER`.
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
| PAPER | Modo simulado; único valor de `mode` aceito no banco |
| REAL | Modo com fundos reais; hoje sempre `REAL_MODE_UNAVAILABLE` |
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
