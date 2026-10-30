# PRD — MemeTrade Analytics v1.3

**Web SaaS | Beta fechado | Solana | Trading real automatizado condicionado a gates | Paper Trading | IA**

## 1. Resumo executivo

Produto: MemeTrade Analytics. Versão: 1.3. Público: traders beta convidados e, posteriormente, assinantes SaaS. Objetivo: descoberta de memecoins na Solana, avaliação por IA, negociação automatizada real e simulada, controles de risco e acompanhamento financeiro auditável. O produto será web e multiusuário desde a primeira entrega; o desenvolvimento inicial ocorrerá localmente em Windows com Docker Compose.

Hipótese de valor: reduzir a fragmentação entre descoberta, avaliação, execução e apuração de resultados. Não há promessa de lucro nem garantia de detecção de fraude.

## 2. Objetivos, métricas e não objetivos

Objetivos: disponibilizar scanner multi-DEX, bots configuráveis, execução paper, P&L verificável, conexão Phantom/Solflare, modelo SaaS e execução real após gates técnicos e legais.

Métricas beta: 100% das operações registradas com idempotência; divergência de saldos conciliada e sinalizada; zero operações acima dos limites autorizados; 100% das transações reais rastreáveis por assinatura; disponibilidade e latência medidas por provedor; precisão do P&L validada em cenários determinísticos.

Fora do escopo inicial: alavancagem, derivativos, múltiplas blockchains, promessa de retorno, copy trading, custódia centralizada, saques administrados pela plataforma e estratégias de manipulação de mercado.

## 3. Perfis, tenants e permissões

Perfis: visitante, usuário beta, assinante, administrador de operações e administrador de segurança. Cada usuário pertence a um tenant lógico; todas as consultas e jobs usam tenant_id validado no servidor. RBAC: usuário opera apenas suas carteiras/bots; suporte acessa somente metadados mínimos; administradores não podem assinar swaps de usuários.

Onboarding: convite, termos e aviso de risco, autenticação por assinatura de mensagem com nonce de uso único, sessão segura, associação de carteiras públicas e configuração de preferências. A assinatura de login não deve ser reutilizada como autorização de trading.

## 4. Scanner multi-DEX

Fontes candidatas: Pump.fun/PumpSwap, Raydium/LaunchLab, Meteora, BONK.fun, Bags; Jupiter para cotações quando aplicável; provedores como DexScreener e Birdeye para dados complementares. Confirmar cobertura, contratos, licenças e rate limits em discovery.

Filtros: idade (1/5/15/30/60 min, 6/24 h e personalizado), liquidez mínima/máxima, volume 5m/1h/24h, compras/vendas, market cap/FDV, holders e crescimento, concentração top 10 excluindo pools conhecidas, percentual do deployer, autoridade mint/freeze, migração de bonding curve, variação de preço, variação de liquidez, risco e origem.

Cada token é identificado por chain + mint; pools têm IDs próprios. Dados ausentes são unknown, nunca zero; mostrar fonte, timestamp, frescor e cobertura. Filtros salvos, watchlist e alertas internos.

## 5. AI Trading Intelligence

Pipeline: ingestão e normalização -> features quantitativas -> filtros de risco -> modelo de sinal -> política determinística -> paper/executor real -> reconciliação -> feedback. Features: momentum, volume relativo, liquidez, volatilidade, slippage estimado, concentração, anomalias e estado do token.

Saída estruturada: token_mint, action (BUY/SELL/HOLD/NO_TRADE), strategy_version, signal_score, confidence_calibration, factors, risks, input_timestamp, expiry, model_version. LLM pode explicar sinais; não assina transações e não contorna políticas. NO_TRADE é o padrão quando faltam dados ou há falha de modelo.

Avaliação: backtest com dados point-in-time, custos e slippage, divisão temporal fora da amostra, paper forward-test, métricas de drawdown, retorno líquido, falsos positivos e drift. Proibir uso de dados futuros em treinamento. Logs permitem reproduzir cada decisão.

## 6. Estratégias e bots

Criar, editar, ativar, pausar e encerrar bots. Parâmetros: universos e fontes, filtros, tamanho máximo por ordem, exposição por token e total, limite diário de perda, drawdown, slippage, taxa máxima, liquidez mínima, cooldown, horário e validade da autorização. Modos REAL e PAPER são explícitos e isolados; troca de modo exige confirmação e nunca migra saldos fictícios.

Máquina de estados: DRAFT -> VALIDATED -> ACTIVE -> PAUSED/STOPPED/EXPIRED; estados de ordens: CREATED -> RISK_APPROVED -> QUOTED -> SIGNED -> SUBMITTED -> CONFIRMED/FAILED/EXPIRED/RECONCILING. Deduplicar sinais, ordens e transações por chave idempotente. Circuit breakers por usuário, estratégia e sistema.

## 7. Carteiras e delegação revogável

Phantom e Solflare conectam via padrão de carteira compatível. Nunca solicitar seed phrase, chave privada ou aprovação genérica irrestrita. Login por mensagem assinada com domínio, nonce, expiração e chain.

Para operação autônoma sem confirmação por ordem, pesquisar e selecionar uma construção de delegação comprovadamente compatível com Solana e com os swaps pretendidos (por exemplo, programa de vault/escrow com políticas on-chain e executor restrito). Aprovação SPL Token delegate isolada não equivale a autorização universal de swap e não garante limites globais. Exigir revisão independente de contratos/programas antes de mainnet.

Política de autorização: usuário define token/ativos permitidos, limite de capital, limite por ordem, limite agregado, vencimento, revogação, destinos e instruções permitidas. Aplicação e, quando possível, programa on-chain devem rejeitar ordens fora da política. Revogação deve impedir novas execuções e ter fluxo documentado para transações já enviadas. Chaves operacionais, se necessárias, protegidas por KMS/HSM e sem poder irrestrito sobre os fundos.

Gate bloqueante: sem mecanismo de delegação auditado e testes de revogação, trading REAL automático permanece desabilitado; PAPER continua operacional.

## 8. Trading real e simulado

Real: verificar autorização válida -> gerar cotação e validade -> calcular impacto/slippage/taxas -> risk engine -> construir transação com instruções permitidas -> assinar somente no contexto autorizado -> enviar -> aguardar confirmação e finalidade configurada -> conciliar ledger e posições. Evitar replay, simular transação antes do envio quando possível, tratar blockhash expirado e transação ambígua. Nunca repetir cegamente um swap após timeout.

Paper: simular fills com spread, impacto, taxas e liquidez; armazenar hipótese de execução e comparar com cotação disponível. Separar completamente fundos, ordens, ledger e P&L de real. Depositar na carteira própria não é depósito para a plataforma; saldo real é consultado on-chain.

## 9. Gestão de risco

Controles mandatórios antes de toda ordem: autorização ativa, token e destino permitidos, saldo disponível, limite por ordem, limite de exposição, perda diária máxima, drawdown máximo, slippage, impacto no preço, liquidez mínima, preço e dados frescos, cooldown e detecção de duplicidade. Controles globais: kill switch, pausa por provedor/RPC, degradação de dados, comportamento anômalo, incidentes de segurança e falhas de reconciliação.

Risk Score 0-100 (maior = maior risco), inicialmente ponderado: concentração 25%, liquidez 25%, autoridades 20%, atividade suspeita 20%, preço 10%; pesos apenas hipótese a calibrar. Score indisponível se cobertura insuficiente; não classificar unknown como seguro.

## 10. Dashboard, P&L e contabilidade

Exibir patrimônio, saldo disponível, capital alocado, posições, P&L realizado e não realizado, taxas, ROI, win rate, drawdown, evolução histórica e resultado por token, bot e período. Separar abas REAL e PAPER com rótulos persistentes.

Ledger imutável por eventos de trade, depósito/retirada observados, taxas, transferência, ajuste e reconciliação. Custo médio ponderado para posição, com política explícita de custo de aquisição e de taxas; precisão decimal e unidades de token. Marcação a mercado com fonte e timestamp, sinalização de preços sem liquidez. Relatórios devem distinguir resultado realizado, estimado e fluxo externo de capital.

## 11. SaaS e assinaturas

Tenancy lógico com tenant_id e row-level security ou filtros server-side testados; autenticação, RBAC, trilhas de auditoria e quotas por plano. Planos candidatos: Beta por convite, Starter, Pro. Entitlements: quantidade de bots, tokens monitorados, filtros, alertas, histórico, acesso a IA e limites de API. Cobrança recorrente por provedor de pagamentos, webhook assinado, idempotência, trial, upgrade/downgrade, inadimplência e cancelamento. Assinatura expirada deve impedir novas operações automatizadas conforme política anunciada, sem bloquear acesso a histórico ou revogação de autorizações. Beta pode operar sem cobrança.

## 12. Arquitetura de referência

Frontend: Next.js + React + TypeScript, Wallet Standard, streaming por SSE/WebSocket. Backend: Java 21 Spring Boot monólito modular (Identity, Tenancy, Wallets, Scanner, MarketData, Signals, Strategies, Risk, Execution, Ledger, Portfolio, Billing, Notifications). Workers assíncronos isolados para ingestão, análise, execução e reconciliação. PostgreSQL para dados transacionais; Redis para cache, locks com cautela e rate limits; fila durável para jobs e eventos; object storage para auditoria e artefatos.

Integrações: adaptadores de descoberta, preços, Solana RPC/WebSocket, roteador de swap e pagamentos. Observabilidade: OpenTelemetry, métricas, logs sem segredos, alertas e tracing. Segredos em gerenciador dedicado; criptografia em trânsito e repouso. Ambiente local Windows: Docker Compose, Node LTS, JDK 21, PostgreSQL, Redis e serviços mock; produção futura em AWS com serviços gerenciados, IaC, CI/CD, backups e disaster recovery.

Diagrama lógico: Browser -> API Gateway/BFF -> módulos Spring Boot -> Postgres/Redis/Queue; Market Ingestion -> Signal/AI -> Risk -> Executor -> Solana -> Reconciliation -> Ledger/P&L -> Dashboard. Execução nunca ocorre no navegador após ativação do bot.

## 13. Modelo de dados lógico

Tenant(id,plan_id,status); User(id,tenant_id,status); Membership(user_id,tenant_id,role); Wallet(id,tenant_id,user_id,address,provider,verified_at); TradingAuthorization(id,wallet_id,policy_hash,scope,limits,expires_at,revoked_at,onchain_ref,status); Bot(id,tenant_id,user_id,mode,strategy_id,status,config_json); StrategyVersion(id,version,params_hash); Token(chain,mint,symbol,metadata); Pool(id,token_mint,venue,address); MarketSnapshot(id,token_mint,pool_id,source,observed_at,price,liquidity,volume); HolderSnapshot(...); RiskAssessment(id,token_mint,score,coverage,factors_json,created_at); Signal(id,bot_id,token_mint,action,confidence,model_version,expires_at); Order(id,tenant_id,bot_id,signal_id,mode,status,idempotency_key,quote_id); Execution(id,order_id,signature,fees,fill_amount,status); LedgerEntry(id,tenant_id,wallet_id,mode,type,amount,asset,reference_id,created_at); Position(id,wallet_id,mode,token_mint,quantity,cost_basis); PortfolioSnapshot(...); Alert(...); Subscription(id,tenant_id,plan_id,status,provider_ref); AuditEvent(id,tenant_id,actor,action,correlation_id,timestamp).

Todas as chaves de negócio incluem tenant_id quando aplicável; unicidade por chain+mint e por assinatura de transação; índices para timestamps, wallet, bot e tenant. Ledger append-only e correções por lançamentos compensatórios.

## 14. APIs propostas (v1)

POST /api/v1/auth/challenge; POST /api/v1/auth/verify; GET /api/v1/me; POST /api/v1/wallets/link; GET /api/v1/wallets; GET /api/v1/wallets/{id}/balances; POST /api/v1/authorizations/prepare; POST /api/v1/authorizations/activate; POST /api/v1/authorizations/{id}/revoke; GET /api/v1/authorizations; GET /api/v1/scanner/tokens; POST /api/v1/scanner/filters; GET /api/v1/scanner/filters; GET /api/v1/tokens/{mint}; GET /api/v1/tokens/{mint}/risk; POST /api/v1/bots; GET /api/v1/bots; PATCH /api/v1/bots/{id}; POST /api/v1/bots/{id}/start; POST /api/v1/bots/{id}/pause; POST /api/v1/bots/{id}/stop; GET /api/v1/bots/{id}/signals; GET /api/v1/orders; GET /api/v1/executions; GET /api/v1/portfolio?mode=REAL|PAPER; GET /api/v1/portfolio/pnl; GET /api/v1/ledger; GET /api/v1/subscription; POST /api/v1/billing/checkout; POST /api/v1/webhooks/billing; GET /api/v1/events (SSE).

Padrão: JWT/sessão segura, validação de tenant, paginação cursor, RFC 7807 para erros, Idempotency-Key para mutações críticas, rate limiting, auditoria, versionamento OpenAPI. Endpoints de execução e assinatura internos, não expostos como livre comando do cliente. Exemplos de payload no anexo técnico posterior.

## 15. Histórias de usuário priorizadas

US01 P0: Como beta, quero aceitar convite e autenticar com minha carteira para acessar minha conta.

US02 P0: Como usuário, quero conectar Phantom/Solflare sem expor chaves.

US03 P0: Como trader, quero filtrar tokens por idade, liquidez, volume, holders e risco.

US04 P0: Como trader, quero salvar filtros e receber alertas internos.

US05 P0: Como trader, quero criar bot PAPER com estratégia e limites.

US06 P0: Como trader, quero visualizar P&L e taxas de paper trading.

US07 P0: Como usuário, quero entender por que a IA emitiu um sinal e quais dados utilizou.

US08 P0: Como usuário, quero pausar o bot e acionar kill switch.

US09 P0: Como usuário, quero configurar autorização REAL limitada e revogável com evidência on-chain.

US10 P0: Como trader, quero que bot REAL execute sem confirmação por operação, mas nunca fora da autorização.

US11 P0: Como usuário, quero conciliação entre ordens, transações e saldos.

US12 P1: Como usuário, quero comparar desempenho REAL e PAPER sem misturar saldos.

US13 P1: Como assinante, quero contratar plano e visualizar limites.

US14 P1: Como administrador, quero gerenciar convites, incidentes e desligamento de execução.

US15 P1: Como auditor, quero reproduzir decisões e rastrear transações sem acessar segredos.

## 16. Critérios de aceite e gates

Scanner: pelo menos duas fontes operacionais validadas, filtros combináveis, atualização com fonte e timestamp, tratamento explícito de unknown.

IA: sinal estruturado, explicação rastreável, validação de dados, sem bypass de risk engine, testes fora da amostra e paper forward-test documentados.

Paper: bots ativos, execução simulada, taxas/slippage modelados, P&L e ledger reconciliáveis.

Wallet: Phantom/Solflare login e vínculo seguros, nonce antirreplay, nenhuma chave privada coletada.

Real: autorização on-chain (ou mecanismo equivalente tecnicamente demonstrado) com escopo, teto e expiração verificáveis; revogação testada; nenhuma ordem acima de limites; auditoria externa de componentes críticos; teste end-to-end com valores limitados; incident response e reconciliação robustos.

SaaS: isolamento multi-tenant com testes negativos, RBAC, quotas, assinatura e cancelamento; usuários de um tenant não acessam dados de outro.

Release gates: G0 viabilidade de APIs e compliance; G1 scanner e paper; G2 arquitetura de delegação e threat model; G3 auditoria/testes e rollout real restrito; G4 assinatura pública e operação SaaS. Falha em gate impede avanço da função afetada.

## 17. Roadmap de MVP e dependências

Sprint 0 (1-2 semanas estimadas): discovery de integrações, restrições jurídicas, threat modeling, seleção e prova de conceito de delegação, definição de métricas.

Sprint 1: monorepo, CI, Docker Compose Windows, identidade, tenants, Wallet Standard, esquema inicial e observabilidade.

Sprint 2: conectores de mercado, normalização, scanner, filtros, watchlist e streaming.

Sprint 3: sinais/IA explicável, risk score, backtesting inicial e política determinística.

Sprint 4: bots PAPER, simulador, ledger, posições, P&L e alertas.

Sprint 5: protótipo de delegação, políticas on-chain, executor, reconciliação e kill switch; condicionado ao Gate G2.

Sprint 6: testes de segurança, auditoria, rollout mainnet restrito e métricas beta; condicionado ao Gate G3.

Sprint 7: billing, planos, suporte, hardening multi-tenant, deploy AWS e abertura gradual SaaS.

Estimativas não são compromissos; revisar após discovery. Trading real não é pré-requisito para demonstrar MVP paper, mas é requisito de produto para o beta real condicionado aos gates.

## 18. Riscos, compliance e decisões pendentes

Riscos: rug pulls, front-running/MEV, volatilidade extrema, baixa liquidez, perdas por slippage, falha de RPC, dados manipulados, comprometimento de executor, bugs de programas e autorização excessiva, divergência contábil, vazamento cross-tenant e falha de billing. Mitigações: limites on-chain, testes adversariais, provedores redundantes, kill switch, auditoria, reconciliação, rate limits e segregação de responsabilidades.

Avaliar com assessoria jurídica a regulação brasileira aplicável (inclusive CVM/BCB conforme natureza do serviço), LGPD, tributação, prevenção à lavagem de dinheiro e obrigações relativas à oferta de serviços a terceiros. A descrição não constitui parecer jurídico.

Decisões abertas: mecanismo exato de delegação, suporte real por DEX/roteador, provedor de mercado e RPC, fornecedor de billing, limites beta, regras de fee e modelo de planos, política de retenção de dados, limites de IA e geografia de lançamento.

## 19. Instrução para AWS AIDLC / Codex

Leia este PRD v1.3 como fonte de requisitos. Execute discovery primeiro: identifique lacunas, valide compatibilidade das APIs e da delegação Solana com Phantom/Solflare, produza threat model, arquitetura, contratos OpenAPI, ERD, backlog e plano de testes. Separe funcionalidades paper de trading real; trate real como bloqueado até prova de segurança, revogação e revisão jurídica. Proponha arquitetura multi-tenant SaaS em Next.js/TypeScript e Spring Boot Java 21, com Docker Compose Windows e migração futura AWS. Não movimente fundos nem implante contratos sem aprovação explícita. Solicite aprovação dos artefatos antes de implementar.

