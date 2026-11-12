# Verificação da passagem Construction → Operation — Termos de uso e LGPD

Conversa: português (Brasil). Data: 2026-11-12.

## Veredito

**PASS, com dois itens que pertencem à Operation.** Todas as Units foram construídas e testadas, nenhuma tabela de rastreabilidade da geração de código tem lacuna (`GAP`), item órfão ou alvo inválido, e os portões da CI cobrem todos os comandos do Build and Test. A rastreabilidade entre Units (`construction/build-and-test/cross-unit-traceability.md`) não passa por um único motivo: a NFR8 tem dois itens `Deferred` cujo dono é um estágio da Operation deste mesmo plano, a Observability Setup. Eles não são achados em aberto da Construction: o código, os indicadores e a linha do alerta já existem; falta provisionar o alarme num ambiente com monitoramento.

## Rastreabilidade da geração de código

| Unit | Linhas | Diferentes de OK ou N/A | Situação |
|---|---|---|---|
| `u1-legal-documents` | 58 | 0 | completa |
| `u2-retention` | 56 | 1: NFR8.5 `Deferred` | alarme `PURGE_STALE`, dono `observability-setup` |
| `u3-reacceptance` | 45 | 0 | completa (Loop-back 1 incluído) |
| `u4-data-requests` | 81 | 1: NFR8.3 `Deferred` | alarme `DATA_REQUEST_OVERDUE`, dono `observability-setup` |
| `u5-anonymization` | 43 | 0 | completa |

## Construção e testes

- Todas as Units aprovadas nos pontos de controle; a U3 foi reaberta pelo Loop-back 1 do Build and Test, corrigida, revisada (READY) e verificada de novo (`bash scripts/verify.sh`, 19 de 19).
- Build and Test aprovado pelo responsável em 2026-11-12: todas as metas aplicáveis `Met` (`construction/build-and-test/build-and-test-summary.md`).
- 87 critérios de aceitação construídos; 37 de 38 requisitos de nível superior cobertos (a exceção é a NFR8 acima).

## Portões da CI

Cada comando do Build and Test tem um portão em `.github/workflows/ci.yml` ou `security.yml` (`construction/ci-pipeline/quality-gates.md`, seção "Correspondência com o Build and Test"). A carga roda só sob demanda (`load-test.yml`), por decisão.

## Condições para a Operation

1. Commit e push das mudanças do Build and Test e do CI Pipeline, pedidos pelo responsável, com a CI e o `security.yml` verdes.
2. Observability Setup provisiona os alarmes `PURGE_STALE` e `DATA_REQUEST_OVERDUE`.
3. Incident Response acrescenta ao runbook RB-09 o passo de troca da chave HMAC da auditoria (U2 NFR6.3).
4. Migração para o Spring Boot 4 antes de 2027-02-09, quando as exceções EX-001 a EX-012 vencem.
