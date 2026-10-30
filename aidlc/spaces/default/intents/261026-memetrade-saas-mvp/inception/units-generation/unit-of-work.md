# Units de trabalho — MemeTrade Analytics (beta PAPER por convite)

Divisão dos 14 componentes de [components.md](../domain-design/components.md) em 9 Units implementáveis e testáveis por conta própria, respeitando as decisões de [decisions.md](../domain-design/decisions.md), os requisitos de [requirements.md](../requirements-analysis/requirements.md) e as histórias de [stories.md](../user-stories/stories.md). As escolhas vêm das respostas Q1 a Q15 de [units-generation-questions.md](units-generation-questions.md). O grafo de dependências está em [unit-of-work-dependency.md](unit-of-work-dependency.md) e a distribuição das histórias em [unit-of-work-story-map.md](unit-of-work-story-map.md).

Este documento descreve o que cada Unit é e o que entrega. A ordem de entrega entre as Units (o que vem primeiro e por quê, além do esqueleto) é decidida no planejamento de entrega.

## Princípios do recorte

- **Por componente de domínio (Q1):** cada Unit de backend agrupa um ou dois componentes do catálogo; as telas do convidado acompanham a Unit da área a que pertencem (Q5, Q14). O painel e a navegação geral ficam numa Unit própria; as telas administrativas ficam na Unit de administração.
- **Quantidade (Q13):** 9 Units, acima do teto inicial de 4 a 6 (Q2), porque o núcleo financeiro em três Units (Q3) e a separação entre auditoria e identidade (Q4) foram mantidos.
- **Núcleo financeiro (Q3):** gestão de bots com motor de decisão; barreira de limites; ledger com P&L. O TDD obrigatório do `team.md` vale para U5 (regras de pausa e perda diária), U6 e U7.
- **Esqueleto primeiro (Q8, Q9, Q15):** a primeira Unit é a fatia fina de ponta a ponta do `team.md`, com a fundação do repositório, o login por carteira e a gravação mínima de auditoria dos eventos de login.
- **Implantação (Q6):** o backend é um único código Spring Boot executado em dois processos (API e worker do ciclo dos bots); há dois apps Next.js separados, convidado e administrador (ADR-009). Cada Unit de backend é um módulo por pacote dentro desse código.
- **Integração (Q7):** chamadas síncronas pela interface pública de cada módulo; eventos em processo para auditoria e painel; REST com contrato e tipos gerados entre frontend e backend. A especificação formal fica para o Contract Design.
- **Fora das Units (Q12):** as investigações SP-1 a SP-5 (FR14.1 a FR14.4, FR15.1) são acompanhadas no planejamento de entrega. REAL e delegação (FR15.3) seguem bloqueados; nenhuma Unit cria caminho de assinatura (FR15.2).

## Tabela de Units

| Unit ID | Directory | Nome | Kind | Componentes | Implantação | Complexidade |
|---|---|---|---|---|---|---|
| U1 | u1-walking-skeleton | Esqueleto de ponta a ponta | service | Fundação do repositório; MarketData (mínimo); Scanner (mínimo); IdentityAccess (login); AuditTrail (gravação mínima); GuestWebApp (casca e lista do scanner) | Cria o processo de API (standalone) e o app web do convidado (standalone) | L |
| U2 | u2-audit-trail | Trilha de auditoria | service | AuditTrail | Módulo embutido no processo de API | S |
| U3 | u3-identity-access | Convites, contas e sessões | service | IdentityAccess (completo); telas do convidado de entrada e conta | Módulo embutido no processo de API; telas no app do convidado | M |
| U4 | u4-discovery | Descoberta e avaliação | service | MarketData (completo); Scanner (completo); RiskAssessment; telas do scanner e do detalhe | Módulo embutido no processo de API; telas no app do convidado | L |
| U5 | u5-bots-decision | Bots e decisão | service | BotManagement; DecisionEngine; telas do assistente, da lista e do bot | Módulo embutido no processo de API e no processo worker (cria o worker); telas no app do convidado | XL |
| U6 | u6-risk-control | Barreira de limites | service | RiskControl | Módulo embutido no processo de API e no worker | M |
| U7 | u7-ledger-pnl | Ledger e P&L | service | PaperTradingLedger; PortfolioPnL; telas de P&L e ledger | Módulo embutido no processo de API e no worker; telas no app do convidado | L |
| U8 | u8-admin-feedback | Administração e feedback | service | Administration; Feedback; AdminWebApp; formulário de feedback do convidado | Módulos embutidos no processo de API; cria o app web do administrador (standalone) | M |
| U9 | u9-dashboard | Painel e navegação | ui | GuestWebApp (painel e menu geral) | Telas no app do convidado | S |

Componentes que se repartem entre Units: MarketData e Scanner começam mínimos em U1 e são completados em U4; IdentityAccess entrega o login em U1 e convites, contas e sessões em U3; AuditTrail grava os eventos de login em U1 e é completado em U2; GuestWebApp é montado por U1, U3, U4, U5, U7, U8 e U9, cada uma com as telas da sua área.

## U1 — Esqueleto de ponta a ponta (`u1-walking-skeleton`)

**Descrição:** a menor fatia integrada que funciona de verdade: navegador → app web do convidado (Next.js) → API (Spring Boot) → PostgreSQL, tudo no Docker Compose local, com a pessoa entrando por assinatura de carteira e vendo a lista mínima do scanner alimentada por uma fonte de mercado simulada.

**Responsabilidades:**
- Fundação do repositório (Q9): monorepo com `apps/web/`, `apps/api/`, `infra/` e `docs/`; `docker-compose.yml`, `.editorconfig` e `.gitattributes` (LF e UTF-8) na raiz; `.env.example` sem valores; hook de pré-commit com varredura de segredos; CI no GitHub Actions com ações fixadas por SHA, `permissions` mínimas, varredura de segredos, de dependências e SAST, lint, formatação, verificação de tipos e piso de cobertura de 80% separado no backend e no frontend.
- Script versionado de verificação ponta a ponta: sobe o ambiente, espera os serviços ficarem saudáveis, roda o smoke que atravessa a fatia e verifica fonte, horário e `unknown`, e devolve código diferente de zero em qualquer falha. O comando exato é autorizado pelo responsável do projeto na Construction.
- MarketData mínimo: interface de adaptador com contrato comum e uma implementação simulada identificada como simulada (FR4.1); tokens por chain + mint (FR4.2); cada valor com fonte, horário UTC e frescor (FR4.3); campo ausente como tipo `unknown` explícito, nunca zero ou `null` (FR4.4).
- Scanner mínimo: lista paginada com ordem determinística e desempate estável (FR5.1) e estados carregando, erro e parcial distintos de `unknown` (FR5.3).
- IdentityAccess (login): desafio com domínio, nonce de uso único, expiração de 5 minutos e chain; verificação da assinatura; sessão que nunca autoriza trading (FR1.3, FR1.4); carteira de administrador lida da configuração do ambiente (AC1.2.6); tenant e usuário sempre do contexto de autenticação (FR2.1).
- AuditTrail mínimo (Q15): gravação append-only dos eventos de login aceito e recusado, com ator, ação, tenant, horário UTC e identificador de correlação, sem nonce nem assinatura.
- GuestWebApp: casca do app do convidado em pt-BR e tema escuro, tela de entrada com assinatura pela carteira do navegador e tela mínima do scanner com fonte, horário e `unknown` visíveis; tipos gerados do contrato da API.

**Fronteiras:** não cria contas por convite (U3), não completa consulta e mascaramento da auditoria (U2), não tem filtros, frescor de 5 minutos, falha de fonte nem divergência (U4), nem indicador de risco (U4). Não chama fornecedor real (C-06) nem usa carteira real: o smoke usa uma carteira de teste gerada só no código de teste, nunca no código de produção.

**Implementação e restrições:**
- O smoke entra com a carteira de administrador de teste definida no `.env` local do ambiente de teste; nenhuma chave ou frase secreta fica no repositório.
- Testes obrigatórios por risco que já nascem aqui: login sem reutilização de assinatura (AC1.2.2, AC1.2.3, AC1.2.4), `unknown` nunca zero (AC2.1.2), isolamento por tenant nas rotas existentes (DP-1, AC1.4.1, AC1.4.2) e REAL inexistente no contrato (AC1.2.4).
- Relógio injetável desde o primeiro módulo; migrações de banco versionadas usadas também pelos testes de integração com PostgreSQL em contêiner.

## U2 — Trilha de auditoria (`u2-audit-trail`)

**Descrição:** completa o AuditTrail como dono único de `AuditEvent` (ADR-006), recebendo os eventos das demais Units por eventos em processo.

**Responsabilidades:**
- Contrato de evento de auditoria usado por todas as Units, com identificador do evento para descartar repetição idempotente (DP-2).
- Mascaramento de endereços de carteira em qualquer saída fora da trilha (4 primeiros e 4 últimos caracteres) e varredura automática de logs e métricas de teste (AC7.3.2, NFR7).
- Garantia de que eventos e logs não contêm segredos, tokens de sessão, cabeçalhos `Authorization`, nonces nem assinaturas (AC7.3.3).
- Consulta restrita da trilha.
- Proteção append-only no banco para `AuditEvent`.

**Fronteiras:** não decide quais ações são sensíveis; cada Unit publica os próprios eventos. Não tem tela própria no beta.

**Implementação e restrições:** a entrega confiável dos eventos em processo (ADR-006, consequência negativa) é tratada no NFR Design; até lá, a Unit expõe a interface e os testes de "exatamente um evento".

## U3 — Convites, contas e sessões (`u3-identity-access`)

**Descrição:** completa o IdentityAccess com convites, criação de conta, aceite de termos, sessões e a tela de conta.

**Responsabilidades:**
- Emitir, listar e revogar convites com uso único e validade; tenant escolhido pelo administrador ao convidar (FR3.1, ADR-002).
- Criação de conta numa única operação: consumir o convite, vincular a carteira e registrar o aceite de termos (AC1.1.3), com concorrência no mesmo convite (AC1.1.4).
- Termos e aviso de risco antes de conectar a carteira (FR1.2).
- Expiração de sessão, retorno à tela de origem e saída (FR1.6, US1.3).
- Tela Conta com carteira abreviada, provedor, validade da sessão, termos aceitos e "Modo REAL: indisponível no beta" (US1.5, Should).
- Contagens de convidados e convites para o estado do sistema.

**Fronteiras:** as telas administrativas de convites ficam em U8; aqui ficam as rotas e a regra de negócio. Não existe "desconectar carteira" no beta (Q13 das histórias).

**Implementação e restrições:** nenhum campo ou rota aceita frase secreta ou chave privada (AC1.2.5); rotas de convite só para administrador, com recusa auditada (AC7.1.4).

## U4 — Descoberta e avaliação (`u4-discovery`)

**Descrição:** completa MarketData e Scanner e entrega o RiskAssessment, com as telas do scanner e do painel lateral de avaliação.

**Responsabilidades:**
- MarketData: frescor com marca de desatualizado acima de 5 minutos (FR4.5), degradação só do que depende da fonte com falha (FR4.6), vários adaptadores simulados, divergência entre fontes acima da tolerância (FR4.7), estado de cada fonte, preço de referência, liquidez e janelas recentes de preço e volume, e retenção de 24 horas (ADR-008).
- Scanner: filtros combináveis com fronteiras inclusivas, exclusão e contagem de `unknown`, estado vazio com "Limpar filtros" e paginação por cursor com novidades em espera (FR5.2, FR5.3).
- RiskAssessment: indicador de 0 a 100 com fatores, pesos versionados, faixas e cobertura mínima; indisponível abaixo do mínimo, gerando NO_TRADE; rótulo "em validação"; textos sem promessa de lucro (FR6.1 a FR6.4).
- Telas S3 (scanner com filtros) e painel lateral do detalhe que preserva posição, filtros e foco (FR5.4).

**Fronteiras:** não decide operações (U5) nem avalia ordens (U6). A divergência (US2.4) depende do resultado de SP-1 para a tolerância e as fontes reais; com adaptadores simulados, a Unit pode ser construída e testada antes.

**Implementação e restrições:** fixtures gravadas para os adaptadores; nenhum acesso de rede real nos testes; mesmos dados e mesma versão de pesos dão o mesmo indicador.

## U5 — Bots e decisão (`u5-bots-decision`)

**Descrição:** gestão de bots com o motor de decisão, coordenando de forma síncrona o ciclo decisão → barreira de limites → execução PAPER (ADR-005), executado no processo worker.

**Responsabilidades:**
- Estratégias modelo (momentum de volume e rompimento de faixa) e assistente em 4 etapas com rascunho salvo no servidor (FR7.1, FR7.2).
- Limites obrigatórios versionados, saldo virtual dentro do teto e recusa de qualquer transferência ou mudança para REAL (FR7.3, FR7.4, AC4.2.5).
- Tabela de transições (DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING, STOPPED), limite de 3 bots ativos com ativações simultâneas e idempotência ao iniciar (FR7.5, FR7.6).
- Motivos de pausa acumulados (pessoa, perda diária, drawdown, sistema); meia-noite UTC remove só "perda diária"; retomada só com limite maior que a perda acumulada (US4.7).
- Pausa e retomada pela pessoa, com a verificação do estado na mesma transação que registra a operação (US4.8).
- Motor de decisão determinístico e explicado, NO_TRADE por padrão com dado `unknown`, desatualizado ou avaliação indisponível, sem modelo de IA (FR8.1, FR8.2, FR8.6).
- Agendador do ciclo no processo worker, no máximo uma decisão por ciclo e retomada segura após reinício (AC4.5.4).
- Encerramento com confirmação, fechamento pelo ledger e estado CLOSING_PENDING sem preço confiável (US4.12).
- Interface de pausa e liberação do sistema para a Administração (motivo "sistema") e contagens de bots por estado.
- Telas do assistente (S4), da lista de bots e do bot (S5) com uso do dia, operações e recusas, e a tela de edição de limites de bot ativo (US4.9, Should).

**Fronteiras:** não avalia limites de ordem (U6) e não grava ordens, lançamentos nem posições (U7); chama as interfaces públicas dessas Units. Não depende da Administração (ADR-007).

**Implementação e restrições:** TDD nas regras de pausa, perda diária e meia-noite UTC (critérios `[TDD]` de US4.5, US4.7, US4.8 e US4.12); teste "pausa impede novas operações" obrigatório; nenhum caminho assina ou envia transação (AC4.3.4).

## U6 — Barreira de limites (`u6-risk-control`)

**Descrição:** o RiskControl, a barreira obrigatória antes de cada ordem simulada (ADR-003).

**Responsabilidades:**
- Verificar tamanho da ordem, exposição por token e total, slippage estimado, liquidez mínima, frescor (até 60 s), intervalo, saldo PAPER, perda diária realizada do dia UTC com taxas e drawdown sobre o maior patrimônio, com fronteiras inclusivas (FR8.3, AC4.6.1, AC4.6.2, AC4.7.1, AC4.7.2).
- Registrar cada recusa com os motivos e a versão dos limites e publicar o evento "ordem recusada" (AC4.6.1).
- Indicar ao coordenador do ciclo quando um limite de pausa foi atingido.

**Fronteiras:** lê exposição, perda do dia e patrimônio do PortfolioPnL (U7) e frescor e liquidez do MarketData (U4); não grava ordens nem pausa bots (quem pausa é U5).

**Implementação e restrições:** núcleo financeiro com TDD; testes da tabela de exemplos de US4.6 escritos antes do código, com valores do design funcional.

## U7 — Ledger e P&L (`u7-ledger-pnl`)

**Descrição:** execução simulada, ledger append-only e posições gravados juntos (ADR-004), mais o P&L que só lê o ledger.

**Responsabilidades:**
- Preenchimento simulado pela política versionada (spread, impacto, taxas, slippage, liquidez) com as hipóteses registradas (FR9.1).
- Gravação atômica de ordem, preenchimento, lançamentos e posição a custo médio ponderado (AC4.11.2).
- Deduplicação por chave de idempotência por tenant e modo, com conflito para mesma chave e conteúdo diferente e unicidade no banco (AC4.6.3, AC4.6.4, AC4.6.5).
- Ledger append-only com `UPDATE` e `DELETE` recusados pelo banco e correção só por lançamento compensatório (FR10.1, AC5.2.2, AC5.2.3).
- Fechamento de posições no encerramento e fechamento pendente concluído uma única vez (AC4.12.2 a AC4.12.4).
- P&L realizado, não realizado e taxas por token, bot e período, com `unknown` sem preço confiável e marcação "sem liquidez" (FR10.2 a FR10.4).
- Exposição, perda realizada do dia UTC, patrimônio virtual e maior patrimônio para a barreira (U6).
- Reconciliação entre operações e ledger visível na tela (FR10.5).
- Telas de P&L (S6), ledger com "ver hipóteses" e reconciliação; rótulo "MODO PAPER (simulado)".

**Fronteiras:** referencia bot, decisão e versão de limites só por identificador, sem ler tabelas de U5 ou U6; os testes usam fixtures e não passam pelo assistente. Nunca assina nem envia transações (FR9.2). Dados PAPER isolados por pessoa e por modo (FR10.6).

**Implementação e restrições:** TDD obrigatório; decimal exato e unidades de token; catálogo de cenários de resultado conhecido (AC5.1.2) com valores do design funcional.

## U8 — Administração e feedback (`u8-admin-feedback`)

**Descrição:** o app web do administrador em endereço separado, com convites, kill switch e estado do sistema, e o fluxo de feedback dos convidados.

**Responsabilidades:**
- Kill switch global: registro com quem, quando e motivo; aciona a pausa de sistema em todos os bots ativos pela interface de U5 e a retira ao desligar, sem retomar bots (FR3.2, ADR-007).
- Estado do sistema só com contagens de convidados, bots por estado, estado do kill switch e última atualização por fonte (AC7.4.1); recusa de qualquer ação sobre bot individual ou leitura de ledger (AC7.4.2).
- Telas administrativas A1 e A2: convites (emitir, listar, revogar com confirmação), kill switch com confirmação, estado do sistema e leitura de feedback como texto puro.
- Feedback: registro de até 2.000 caracteres com tela de origem e horário, visível só ao administrador; formulário no app do convidado que preserva o texto em falha (FR13.1, Should).
- Eventos de auditoria de toda ação administrativa (FR3.3).

**Fronteiras:** não acessa ledger, operações nem carteiras; o motivo de pausa "sistema" vive nos bots (U5). O item de menu do feedback entra na navegação de U9.

**Implementação e restrições:** cria o segundo app Next.js; rotas administrativas não ficam no código entregue aos convidados (ADR-009).

## U9 — Painel e navegação (`u9-dashboard`)

**Descrição:** a primeira tela depois do login e a navegação geral do app do convidado.

**Responsabilidades:**
- Painel com estado vazio e atalhos, resumo dos bots ativos e pausados, última operação e atalho "Pausar" pela interface de U5 (FR12.1, US5.3).
- P&L simulado e avisos de dados atualizados sem recarregar, com "Atualizado às HH:MM", a partir dos eventos em processo (FR12.2, US5.4).
- Menu geral do app do convidado, com h1 igual ao item de menu e regiões nomeadas (CT-UX-9).

**Fronteiras:** não calcula P&L nem altera bots; só lê das interfaces de U4, U5 e U7.

**Implementação e restrições:** carregando, erro e `unknown` como estados visíveis distintos; falha num quadro não esconde os demais (AC5.3.3).

## Fora das Units

| Item | Onde fica |
|---|---|
| SP-1 a SP-5 (FR14.1 a FR14.4, FR15.1) | Investigação acompanhada no planejamento de entrega (Q12); US2.4 em U4 depende do resultado de SP-1 para tolerância e fontes reais |
| FR15.3 (REAL e delegação) | Bloqueado até G2/G3, revisão independente, análise jurídica e aprovação explícita |
| Cobrança, filtros salvos, alertas, métricas avançadas | Fora do beta (Won't) |
