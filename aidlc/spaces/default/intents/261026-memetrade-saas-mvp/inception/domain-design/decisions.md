# Decisões de arquitetura — Design de Domínio

Registro das decisões que definem os componentes de [components.md](components.md), com base em [requirements.md](../requirements-analysis/requirements.md), [stories.md](../user-stories/stories.md), [team-practices.md](../practices-discovery/team-practices.md) e nas respostas Q1 a Q8 de [domain-design-questions.md](domain-design-questions.md). Data: 26/10/2026.

## ADR-001: Componentes recortados para o beta PAPER

### Status
Accepted

### Context
O PRD lista 13 módulos (Identity, Tenancy, Wallets, Scanner, MarketData, Signals, Strategies, Risk, Execution, Ledger, Portfolio, Billing, Notifications). O beta aprovado é PAPER por convite, sem REAL, sem cobrança e sem modelo de IA (FR8.6, FR15.3).

### Decision
Definir 14 componentes para o beta: IdentityAccess, AuditTrail, MarketData, Scanner, RiskAssessment, BotManagement, DecisionEngine, RiskControl, PaperTradingLedger, PortfolioPnL, Administration, Feedback, GuestWebApp e AdminWebApp. Execução REAL, autorização on-chain, cobrança e notificações não entram.

### Consequences
- Positivo: o catálogo cobre só o que o beta entrega e reduz o que um desenvolvedor solo precisa manter.
- Negativo: REAL e cobrança vão exigir novos componentes depois (por exemplo, autorização, executor real, cobrança); a fronteira de ledger por modo já está preparada.
- Segurança e conformidade: sem componente de execução REAL, não existe caminho de código para assinar transações (FR15.2).

### Alternatives Rejected
- **Catálogo completo do PRD já no beta:** criaria componentes sem uso e convidaria implementação antecipada de REAL, contra as regras rígidas do projeto.
- **Menos componentes (um "Trading" único):** misturaria decisão, risco e ledger, dificultando o TDD do núcleo financeiro.

## ADR-002: Tenant compartilhado com isolamento por pessoa

### Status
Accepted

### Context
O PRD prevê tenant lógico com membros. A resposta Q1 escolheu tenants compartilhados desde o beta; a Q8 manteve o isolamento por pessoa (US1.4). O administrador escolhe o tenant ao emitir o convite.

### Decision
Tenant agrupa pessoas para administração e cobrança futura. Todo dado de negócio (bots, decisões, ordens, ledger, posições, P&L, feedback) carrega `tenantId` e `ownerUserId`, e as consultas filtram pelos dois, a partir do contexto de autenticação.

### Consequences
- Positivo: o modelo já suporta equipes e planos por tenant sem migração de dados.
- Negativo: toda consulta precisa de dois filtros; os testes negativos cobrem outro tenant e outra pessoa do mesmo tenant.
- Segurança e conformidade: o filtro por pessoa reduz exposição de dados de carteira (LGPD); o teste negativo por ID vale também entre membros do mesmo tenant.

### Alternatives Rejected
- **Um tenant por convidado:** mais simples, mas rejeitado pela decisão Q1.
- **Tenant como equipe que compartilha tudo:** mudaria US1.4 e exporia resultados entre pessoas; rejeitado na Q8.

## ADR-003: Dois componentes de risco

### Status
Accepted

### Context
Existem dois "riscos": o indicador de 0 a 100 do token (FR6) e a barreira de limites antes de cada ordem (FR8.3). Mudam por motivos diferentes: calibração dos pesos versus regras de segurança financeira (Q2).

### Decision
Separar RiskAssessment (indicador) e RiskControl (barreira).

### Consequences
- Positivo: a barreira, parte do núcleo financeiro com TDD, evolui sem tocar na calibração do indicador.
- Negativo: mais um componente e uma dependência a manter.
- Segurança e conformidade: a barreira fica num único lugar auditável; nenhuma ordem chega à execução sem passar por ela.

### Alternatives Rejected
- **Um único componente "Risco":** misturaria ciclos de mudança e aumentaria o risco de alterar a barreira ao ajustar o indicador.

## ADR-004: Execução PAPER e ledger no mesmo componente

### Status
Accepted

### Context
AC4.11.2 exige que ordem, preenchimento, lançamentos e posição sejam gravados juntos ou nada. A regra do domínio evita transação que atravesse componentes (Q3).

### Decision
PaperTradingLedger é dono de PaperOrder, Fill, LedgerEntry, Position e SimulationPolicy e grava tudo numa única operação. PortfolioPnL é separado e só lê o ledger.

### Consequences
- Positivo: atomicidade simples, sem reconciliação entre componentes no caminho normal.
- Negativo: o componente fica maior; a separação futura exigiria eventos com reconciliação.
- Segurança e conformidade: imutabilidade do ledger e idempotência ficam num único dono, mais fácil de testar no banco (AC5.2.2).

### Alternatives Rejected
- **Execução e ledger separados, sincronizados por eventos com reconciliação:** introduz estados intermediários e divergências no caminho normal.

## ADR-005: Ciclo síncrono coordenado pela gestão de bots

### Status
Accepted

### Context
A regra rígida exige verificar limites e deduplicar antes de cada ordem. A pausa precisa barrar a ordem na mesma transação (AC4.8.2). Q4 escolheu o fluxo síncrono.

### Decision
BotManagement coordena, por ciclo, DecisionEngine → RiskControl → PaperTradingLedger de forma síncrona. Eventos servem só para painel e auditoria.

### Consequences
- Positivo: "nenhuma ordem sem barreira" é verificável por construção; testes de ponta a ponta do ciclo são diretos.
- Negativo: um ciclo lento atrasa o próprio bot; a capacidade de 60 bots ativos (NFR2) precisa ser verificada no design de NFR.
- Segurança e conformidade: o estado de pausa é conferido dentro da mesma operação que registra a ordem.

### Alternatives Rejected
- **Tudo por eventos assíncronos:** mais escalável, mas exige provar que nenhuma ordem passa sem barreira em todas as ordens de chegada de eventos.

## ADR-006: Trilha de auditoria central alimentada por eventos

### Status
Accepted

### Context
DP-2 exige exatamente um evento por ação sensível, sem segredos, com endereço mascarado fora da trilha (Q5).

### Decision
AuditTrail é o único dono de AuditEvent e recebe eventos dos demais, descartando repetições pelo identificador do evento.

### Consequences
- Positivo: um único ponto para mascaramento, retenção e consulta restrita.
- Negativo: a entrega do evento precisa ser confiável; perda de evento é uma falha de auditoria a tratar no design de NFR.
- Segurança e conformidade: facilita a política de retenção da LGPD e a auditoria independente futura.

### Alternatives Rejected
- **Cada componente grava sua própria trilha:** espalha mascaramento e retenção e dificulta reconstruir uma sequência.

## ADR-007: Administração fina e kill switch aplicado por comando

### Status
Accepted

### Context
Q6 colocou convites em IdentityAccess e o kill switch e o estado do sistema numa Administração fina. Se BotManagement consultasse a Administração e a Administração consultasse BotManagement, haveria um ciclo de dependências.

### Decision
Administration é dona do registro KillSwitch e aplica a pausa de sistema chamando BotManagement, que guarda o motivo "sistema" nos próprios bots. BotManagement não depende da Administração. O estado do sistema usa só contagens.

### Consequences
- Positivo: grafo sem ciclos; o motivo de pausa vive junto do bot, onde a transição é verificada.
- Negativo: a pausa de sistema precisa ser aplicada a todos os bots ativos de forma confiável; falha parcial deve ser detectada e repetida.
- Segurança e conformidade: a Administração não lê ledger, operações nem carteiras (AC7.4.2).

### Alternatives Rejected
- **Administração dona de convites, kill switch e estado:** concentraria responsabilidades de identidade fora de IdentityAccess.
- **BotManagement consultando a Administração a cada ciclo:** criaria um ciclo de dependências.

## ADR-008: Histórico de mercado de 24 horas

### Status
Accepted

### Context
As estratégias precisam de janelas recentes de preço e volume (AC4.5.3), e o P&L precisa de marcação a mercado. Guardar tudo aumenta custo e retenção (Q7).

### Decision
MarketData guarda observações por 24 horas (proposta inicial) e descarta o restante; o prazo final sai do design funcional e do inventário de dados (SP-3).

### Consequences
- Positivo: volume de dados controlado no ambiente local.
- Negativo: backtest e histórico longo exigirão outra decisão quando entrarem.
- Segurança e conformidade: dados de mercado não são pessoais, mas a retenção curta simplifica o inventário.

### Alternatives Rejected
- **Guardar todo o histórico observado:** custo e volume sem uso no beta.

## ADR-009: Duas interfaces web separadas

### Status
Accepted

### Context
Os mockups refinados (Q3) colocaram a área administrativa em endereço separado, com layout próprio.

### Decision
GuestWebApp e AdminWebApp são componentes distintos, cada um chamando só os componentes de que precisa.

### Consequences
- Positivo: permissões e superfície de ataque menores em cada interface.
- Negativo: duas bases de interface para manter.
- Segurança e conformidade: rotas administrativas não ficam no código entregue aos convidados.

### Alternatives Rejected
- **Uma interface com área administrativa no mesmo menu:** rejeitada na decisão Q3 dos mockups refinados.
