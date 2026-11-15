# Evidências — Practices Discovery (re-run, Trading REAL)

Conversa: português (Brasil). Execução avulsa (`single`) só do líder (`aidlc-pipeline-deploy-agent`), sem
colaboradores (Collaborators: off), para registrar as mudanças de regra do ADR-008 antes da Construction. Rascunho do
líder em 2026-11-14, commit `40e2553`; integração final depois da entrevista (`practices-discovery-questions.md`, Q1
a Q4, todas respondidas A pelo fundador).

## Sources

- `aidlc/spaces/default/memory/team.md` — práticas afirmadas, base do re-run (cinco seções).
- `aidlc/spaces/default/memory/project.md` — regras vigentes de `## Mandated` e `## Forbidden`, para não repetir nem contradizer.
- `inception/domain-design/decisions.md` — ADR-004 (política de conteúdo da assinatura), ADR-005 (modelo de ameaças, G2), ADR-008 (regras alteradas pelo fundador), ADR-010 (assinatura em processo isolado).
- `inception/contract-design/contract-summary.md` — regras "Ligado no ambiente", "Liberação", "Termos" e "Flag fora do HTTP"; rotas `/api/v1/real/bot-wallet` e `/api/v1/real/bot-wallet/withdrawals[/{withdrawalId}]`; pendência R2.
- `inception/delivery-planning/bolt-plan.md` — B1 como esqueleto, definição de pronto, demonstração e marcos de rede.
- `inception/requirements-analysis/requirements.md` — NFR8 (qualidade) e restrições TR-C15, TR-C16, TR-C20.
- `inception/units-generation/unit-of-work.md` — U1 `u1-bot-wallet` como a menor fatia integrada.
- Artefatos de código em `aidlc/spaces/default/codekb/eureka-trader-ai/`: **code-structure**, **technology-stack**, **dependencies**, **code-quality-assessment**, **architecture**, **business-overview**.
- Repositório: `.github/workflows/` (jobs `ci`, `security`, `load-test`), `scripts/verify.sh`, `git log` (HEAD `40e2553`).

## O que o código mostra (evidência)

| Prática | Evidência | Artefato |
|---|---|---|
| Trunk-based em `main`, commits pedidos, histórico linear | branch atual `main`; commits recentes direto em `main` (`40e2553`, `9b2695c`, `e6c634a`) | git |
| Monorepo `apps/api`, `apps/web`, `apps/admin`, `infra`, `docs`, `scripts` | layout do repositório; o app administrativo já existe como terceiro app | code-structure |
| Java 21, Spring Boot 3.5, Node 24 por `.nvmrc`, pnpm 10.18 por `packageManager`, `--frozen-lockfile` | versões fixadas | technology-stack |
| Sem SDK Solana na produção; `solanaj` só em escopo `test`, banido pelo enforcer; Ed25519 nativo do JDK; sem KMS nem cliente HTTP de saída hoje | o REAL precisa de adaptadores novos (Jupiter, RPC, KMS) atrás de porta, como o ADR-013 prevê | technology-stack, dependencies |
| Monólito modular, módulos por pacote, comunicação só por `api` | tabela de dependências entre módulos; `shared` sem dependência de negócio | dependencies, architecture |
| REAL hoje só como valor proibido | `CHECK (mode = 'PAPER')` em 13 tabelas, `ModeGuardFilter` (`403 REAL_MODE_UNAVAILABLE`), `PaperExecution` | architecture, business-overview |
| Testes por risco já nomeados; ArchUnit em 9 classes; JaCoCo 80% mesclado; Jest com 80% nos dois apps; 15 specs Playwright | contagens e regras | code-quality-assessment |
| Lint, formatação, tipos, Jest com piso e `check:api` rodam para `apps/web` e `apps/admin`; `verify.sh` roda na CI; Gitleaks, CodeQL, Dependency-Check e auditoria pnpm no job `security` | `.github/workflows/*.yml` | repositório, code-quality-assessment |
| Atores atuais: convidado, administrador, sistema; sem cobrança | o REAL acrescenta a carteira do bot sem criar papel novo | business-overview |

## Mudanças em relação ao `team.md` atual

1. **Deployment — Gates de REAL (G2/G3)**: troca "autoaprovação não satisfaz G2/G3; revisor independente (C-14)" por: o fundador é o revisor único dos gates do REAL nesta fase; G2 cumprido pelo ADR-005; G3 dispensado e reavaliado com o crescimento; cobrança pública e implantação on-chain continuam bloqueadas. Origem: ADR-008; requisitos TR-C15.
2. **Deployment — Rollout do REAL** (bullet novo): validador local e devnet → carteira do fundador na mainnet → até 20 convidados, quarentena de 24 h antes de cada liberação. Origem: ADR-008; `bolt-plan.md` (Marcos de rede); TR-C16.
3. **Deployment — acréscimos do líder (confirmados na Q2)**: conta AWS nova para o KMS (TR-C20) em "Situação atual"; no rollback do REAL, desligar o flag ou acionar o kill switch REAL antes de tudo, com o saque sempre disponível (contract-summary, "Flag fora do HTTP"); topologia do TxSigner decidida no Infrastructure Design (ADR-010).
4. **Testing Posture — termos**: o teste "nova versão dos termos exige novo aceite antes de qualquer outra rota" ganha a exceção para `GET /api/v1/real/bot-wallet` e as rotas de saque, e passa a cobrir as duas metades. Origem: contract-summary (regra "Termos", pendência R2).
5. **Testing Posture — testes obrigatórios do REAL** (NFR8): o item "REAL desligado por padrão" fica mais preciso (nenhuma carteira criada e nenhuma assinatura pedida ao KMS, em API, worker e TxSigner); itens novos: saque só para a carteira principal (e disponível com kill switch, REAL desligado e sem liberação), política do TxSigner recusando transação fora da lista (ADR-004), uma única transação por ordem (FR5.5).
6. **Testing Posture — Ordering**: inclui a lógica da carteira do bot, do saque e da política de assinatura do TxSigner (e os estados e a idempotência das ordens REAL) entre o que tem teste antes do código. Origem: NFR8; `bolt-plan.md` B1 e B4. `Methodology` continua `custom`.
7. **Testing Posture — Determinismo e piso** (acréscimos do líder, confirmados na Q2): validador local, KMS simulado e respostas gravadas da Jupiter e do RPC; nenhum teste da CI usa devnet, mainnet nem KMS real; o processo TxSigner também segue o piso de 80%.
8. **Walking Skeleton**: bullet novo com a fatia da U1/B1 (ativar, depositar, sacar e ver a recusa para outro endereço no validador local), verificada sempre por `bash scripts/verify.sh`. Origem: `bolt-plan.md` B1; `unit-of-work.md` U1.
9. **Way of Working — Cobertura da CI** (Q1): o bullet "Lacunas da CI (intent `261109-termos-lgpd`)" foi trocado por "Cobertura da CI": a CI já roda lint, formatação, tipos, Jest com piso e `check:api` de `apps/web` e `apps/admin`; só o responsável altera o `ci.yml`. Evidência: `.github/workflows/ci.yml`; code-quality-assessment.
10. **Walking Skeleton — Verificação** (Q1): "comando a definir" virou `bash scripts/verify.sh`, que já roda na CI. Evidência: job do `ci.yml` "Walking-skeleton verification"; code-structure (`scripts/verify.sh`).
11. **Walking Skeleton — frequência do spec da carteira** (Q3): o spec da carteira com o validador local roda em toda mudança e no merge; se a cota do GitHub Actions apertar, a frequência é revista com dados.
12. **Code Style — Fronteiras de módulos** (Q1): "sem verificação automática" virou "verificadas por ArchUnit"; módulo novo ganha a sua regra. Evidência: architecture (estilo arquitetural), code-quality-assessment (9 classes ArchUnit).
13. **Deployment — condições dos convidados** (Q4): quarentena de 24 h, pelo menos 7 dias da carteira do fundador na mainnet sem divergência e parecer de custódia decidido viram prática do Deployment.

## Regras novas para `project.md` (só por acréscimo)

| Regra nova | Como se relaciona com a regra vigente |
|---|---|
| ALWAYS G2 cumprido pelo ADR-005, G3 dispensado, REAL automático liberável nos três modos após liberação com quarentena de 24 h | esclarece "ALWAYS manter o trading REAL automático desabilitado até que os gates G2 e G3 ... sejam cumpridos": nesta fase, o fundador considera os gates cumpridos/dispensados; a regra vigente continua valendo como condição |
| ALWAYS chave da carteira do bot só no KMS, só o TxSigner assina | esclarece "NEVER incluir no código de produção APIs de importação ou geração de chave secreta" e "NEVER ... armazenar ... chave privada do usuário": a geração acontece dentro do KMS, e a chave é do sistema, não do usuário |
| ALWAYS saque disponível a qualquer momento | aplica ao REAL a regra "Termos" do contrato e o FR3.3; não enfraquece nenhuma regra vigente |
| NEVER navegador monta ou envia transação no modo Assinada | esclarece "NEVER executar ordens no navegador depois que o bot é ativado": assinar não é executar |
| NEVER TxSigner fora da política ou saque para outro endereço | aplica ao REAL "NEVER ... aprovação genérica irrestrita" e "NEVER movimentar fundos reais sem aprovação explícita" |

Regras do ADR-008 que **continuam** sem mudança e por isso não geram regra nova: sem SDK Solana na produção, sem
aprovação genérica irrestrita, sem movimentar fundos sem aprovação explícita (cada liberação aprovada pelo fundador).

## Decisões da entrevista

| Pergunta | Resposta | Efeito |
|---|---|---|
| Q1 Atualizar três textos desatualizados | A | Way of Working (Cobertura da CI), Walking Skeleton (`bash scripts/verify.sh`) e Code Style (ArchUnit) atualizados |
| Q2 Confirmar acréscimos do líder | A | rollback do REAL por flag ou kill switch mantendo o saque; piso de 80% no TxSigner; nenhum teste da CI com devnet, mainnet ou KMS real |
| Q3 Frequência do spec da carteira | A | roda em toda mudança e no merge; revisão com dados se a cota apertar |
| Q4 Condições dos convidados | A | quarentena de 24 h, 7 dias do fundador sem divergência e parecer de custódia ficam no Deployment |

## Assumptions & Open Questions

- **Cota do GitHub Actions**: continua "a definir" (Way of Working, Hospedagem); o validador local em toda mudança aumenta o consumo, e a frequência é revista com dados se a cota apertar (Q3).
- **Parecer de custódia**: virou condição de Deployment (Q4), não regra de `project.md`; o parecer em si continua pendente.
