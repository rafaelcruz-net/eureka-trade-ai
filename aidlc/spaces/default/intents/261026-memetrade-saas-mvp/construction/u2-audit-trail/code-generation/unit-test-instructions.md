# Instruções de testes — U2 Trilha de auditoria

Como preparar, rodar e medir os testes da Unit `u2-audit-trail`, conforme o [plano de geração de código](code-generation-plan.md). A estratégia de testes do workflow é `Comprehensive`: 10 a 15 testes por componente, com unitários, integração e ponta a ponta, mais desempenho e segurança onde os NFRs pedem. O piso de 80% de linhas do escopo `enterprise` soma-se a ela. A U2 não faz parte do núcleo financeiro, então todos os passos seguem a ordem test-after do `team.md`.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml`: Surefire para unitários (`*Test`), Failsafe para integração (`*IT`), JaCoCo `check` com 80% de linhas no `verify` completo |
| Base de integração | `AbstractPostgresIT` | PostgreSQL com os parâmetros da U2, script de preparo dos papéis como superusuário do contêiner, migrações com o login do dono, aplicação e retenção com os seus logins |
| Smoke | Docker Compose, Playwright | `bash scripts/verify.sh` com `db-bootstrap` e `db-migrate` |
| Desempenho | Docker Compose, SQL, consultas HTTP | `bash scripts/perf-audit.sh`, só local |

Pré-requisitos: Docker Desktop em execução, JDK 21, Git Bash no Windows. Para o script de desempenho, o ambiente de uso diário precisa estar desligado e o disco precisa de cerca de 30 GB livres.

## Comandos desta Unit

Todo teste da U2 leva a tag JUnit `u2`. Os comandos abaixo rodam só os testes desta Unit, com unitários e integração, sem a regra de cobertura. A cobertura vale para a suíte inteira e é conferida pelo `verify` completo do job `backend` e pelo Build and Test.

| O que roda | Comando exato |
|---|---|
| Backend da U2, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u2 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Backend da U2, uma classe só | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u2 -Dit.test=AuditRetentionPurgeIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Cobertura da suíte inteira (piso de 80%) | `apps/api/mvnw -f apps/api/pom.xml verify` |
| Smoke da fatia com a trilha (sobe, testa, derruba) | `bash scripts/verify.sh` |
| Desempenho da trilha (só local) | `bash scripts/perf-audit.sh` |

O comando do backend da U2 fica pronto no Passo 2, antes do primeiro teste da Unit (Passo 3).

## Metas de cobertura

- 80% de linhas no backend (JaCoCo `check`), medido sobre a suíte inteira.
- Exclusões fixas, só na configuração da ferramenta, como na U1: `MemetradeApplication`, classes `*Config` e `*Properties`, `HealthProbe`. A U2 não acrescenta exclusões.
- O piso nunca é reduzido para fazer um passo passar; a falta de cobertura é registrada no resumo do código.

## Testes por componente

| Componente | Tipo | Quantidade prevista | Casos de risco obrigatórios |
|---|---|---|---|
| Papéis, posse e migração | Integração | 15 | Trilha sem edição (`UPDATE` e `TRUNCATE` recusados, inclusive para o dono); `DELETE` só da retenção e antes do corte; aplicação sem DDL e sem assumir `audit_retention`; transição de banco da U1; preparo idempotente |
| Parâmetros do PostgreSQL | Integração | 3 | Sem log de instruções e parâmetros; `track_commit_timestamp` ligado |
| Publicador | Unitário e integração | 15 | Nenhum segredo, token, nonce ou assinatura no evento; exatamente um evento; ação desfeita sem evento; tenant do `Principal` |
| Relay e medida | Unitário e integração | 10 | Entrega única mantida; horário de commit nulo; contador diário |
| Consulta (domínio e repositório) | Unitário e integração | 15 | Período de até 31 dias; ordem e cursor estáveis; todos os tenants; plano sem ordenação à parte |
| Rota administrativa | Integração e contrato | 12 | `401`; `403` de convidado auditado; `AUDIT_QUERIED` sem resultados; `500` sem dados; `no-store`; OpenAPI administrativo separado |
| Estado e execução única | Integração | 8 | Uma execução com duas chamadas simultâneas |
| Expurgo | Integração | 12 | Fronteira do corte; nada apagado dentro dos 12 meses; falha e limite de tempo; evento do expurgo |
| Limpeza | Integração | 8 | Pendente nunca apagada |
| Recuperação e saúde | Unitário e integração | 10 | Banco sem registro; 24 h e 26 h |
| Métricas e logs | Integração | 8 | Nenhum rótulo com dado pessoal |
| Arquitetura e configuração | ArchUnit e unitário | 6 | `Role.ADMIN` só na autorização; retenção só no expurgo; perfil padrão sem credencial do dono |
| Varredura | Integração | 6 | Logs da aplicação e do banco, métricas, `payload` e `details` |
| Compose e CI | Unitário (leitura de arquivos) | 8 | Credenciais por serviço nas duas composições; ordem de subida; healthcheck TCP |
| Smoke | `scripts/verify.sh` | 1 execução | `403` auditado; saúde da trilha sem indicador degradado |
| Desempenho | `scripts/perf-audit.sh` | 1 execução | p95 da consulta até 2 s; 99% das entregas em até 5 s; expurgo dentro de 30 minutos |

## Mocks e dublês

- **Relógio:** `MutableClock` injetado em todo teste de prazo: corte de 12 meses, 7 dias da limpeza, 24 h e 26 h da saúde, 30 minutos de limite, virada do dia UTC. Nenhum teste usa `Thread.sleep` para esperar prazo de domínio. O limite de tempo é testado com um valor reduzido na configuração do teste, nunca no código de produção.
- **Gatilho do banco:** usa o relógio do banco; os testes usam eventos de 13 e de 11 meses, longe da fronteira.
- **Banco:** PostgreSQL real em contêiner com as migrações da aplicação e os três logins; cada teste cria os próprios dados e não depende de ordem nem de estado de outro teste.
- **Administrador:** sessão de administrador criada só no código de teste; nenhuma classe de produção cria esse contexto.
- **Falhas:** a falha de um lote do expurgo e a falha ao gravar `AUDIT_QUERIED` são provocadas por um dublê do repositório ou por revogação temporária de privilégio no teste, nunca por código de produção condicional.

## Dados de teste

- Endereços de carteira e identificadores vêm de valores sintéticos ou de chaves geradas no teste; nada real.
- A varredura registra os valores sensíveis emitidos pelos testes (tokens, nonces, assinaturas, endereços completos) para procurá-los no fim da suíte.
- A carga sintética do desempenho usa só valores gerados e roda num volume próprio, apagado no fim.
- Teste instável é tratado como defeito e corrigido, nunca ignorado.
