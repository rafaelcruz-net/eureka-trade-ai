# Relatório de desvios

Conversa: português (Brasil). Resposta 3A. Diferenças entre o que as Units desenharam (`construction/<unit>/infrastructure-design/` e `nfr-design/`) e o que foi entregue na release `v2026.11.12-1` (`operation/deployment-execution/deployment-log.md`). Cada desvio foi decidido no Build and Test ou depois, com o motivo registrado.

## Desvios

| Item | Desenho | Entregue | Motivo | Onde está registrado |
|---|---|---|---|---|
| Pool de conexões da `api` | `minimum-idle: 5`, máximo 20 | pool fixo: `minimum-idle` = `maximum-pool-size` (20); 5 só nos testes | a rajada fria abria conexões dentro da trava de novo aceite (U3 NFR3.2) | `apps/api/src/main/resources/application.yml`; Loop-back 1 |
| Aquecimento da trava | não existia | `TermsReacceptanceWarmup` na subida da `api` | trava de 7,0 ms para 4,2 ms (meta 5 ms) | plano de código da U3, Passo 15 |
| Histograma da espera por conexão | não publicado | `hikaricp.connections.acquire` com histograma; o cenário `reacceptance` informa `acquireP95Ms` | separar espera de conexão e consulta | plano de código da U3, Passo 16 |
| Versões de dependências | as do BOM do Spring Boot 3.5.16 | Tomcat 10.1.61, pgjdbc 42.7.14, Kotlin 2.4.21 (teste), Log4j 2.25.5 | CVSS ≥ 7 no Dependency-Check | `apps/api/pom.xml`; `construction/ci-pipeline/ci-config.md` |
| Exceções de segurança | nenhuma | EX-001 a EX-013 até 2027-02-09 | Spring 6.2.19 sem versão aberta corrigida; falso positivo do Prometheus | `security/exceptions.yml` |
| Analisador OSS Index | ligado | desligado | `401 Unauthorized` da Sonatype; decisão do responsável | `construction/ci-pipeline/ci-config.md` |
| Caminho das exceções na CI | `../../dependency-check-suppressions.xml` | `${GITHUB_WORKSPACE}/dependency-check-suppressions.xml` | a CI nunca lia o arquivo | `.github/workflows/security.yml` |
| Medição da abertura do painel (U9 do intent anterior) | portão de 2 s | medição informativa | decisão Q1 = B do responsável | NFR3.1 da U9 |

## Sem desvio

Topologia do Compose, portas só em `127.0.0.1`, papéis do banco, migrações `0012` a `0015`, chave HMAC obrigatória em `api` e `worker`, métricas e saúde dos `monitoring-design.md`: entregues como desenhados (`ComposeFilesTest`, suíte e ensaio de rollback).
