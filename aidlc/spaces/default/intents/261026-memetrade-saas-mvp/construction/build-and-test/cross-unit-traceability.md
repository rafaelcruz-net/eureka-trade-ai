# Rastreabilidade entre Units

Conversa: português (Brasil). Gerado a partir de `aidlc engine sensor-traceability --stage build-and-test` (2026-11-06).

## Veredito

**Reprovado.** De 215 elementos (FR, AC e grupos de NFR), 180 estão cobertos e 35 não.

A maior parte das lacunas vem do formato dos registros de rastreabilidade da geração de código, não de código ausente. Mesmo assim, cada ID não coberto é um achado deste estágio e entra na volta 1 (ver `test-results.md`).

## Elementos não cobertos

| ID | Unit dona | Por que aparece sem cobertura |
|---|---|---|
| FR12.1, AC5.3.1–AC5.3.3 | u9-dashboard | `u9-dashboard/code-generation/traceability.json` usa o formato `links` (`source`/`target`) em vez de `upstream_ids` + `coverage` (`id`/`status`/`target`); o leitor recusa o arquivo inteiro |
| FR12.2, AC5.4.1–AC5.4.3 | u9-dashboard | mesmo motivo |
| FR13.1, AC6.1.1–AC6.1.4 | u8-admin-feedback | `u8-admin-feedback/code-generation/traceability.json` no mesmo formato `links`, recusado |
| FR14.1–FR14.4, FR15.1 | u8-admin-feedback | mesmo motivo; nenhum AC chega até eles |
| FR2.2 (via AC7.4.1, AC7.4.2) | u8-admin-feedback | mesmo motivo; AC1.4.x da U1 estão cobertos |
| NFR1 a NFR14 (grupos) | várias | cada grupo reprova se algum NFR de alguma Unit não está construído; detalhe abaixo |

## Detalhe dos NFR não construídos, por Unit

| Unit | Qtd. | Motivo |
|---|---|---|
| u4-discovery | 46 | `code-generation/traceability.json` não tem linhas de NFR; elas só estão em `nfr-design/traceability.json`, que o leitor deste estágio não usa |
| u8-admin-feedback | 35 | arquivo recusado (formato `links`) |
| u9-dashboard | 20 | arquivo recusado (formato `links`) |
| u2-audit-trail | 9 | NFR12.1, 3.1, 3.3, 4.2 e 9.1 têm `target` com texto livre misturado aos caminhos, então o leitor não acha o arquivo (que existe); NFR2.3, 3.2, 6.8 e 10.1 estão ausentes do arquivo |
| u5-bots-decision | 9 | `Deferred` para a carga `u5` (NFR2.1–2.6, 3.1, 3.2), que falhou na semente, para a CI no Linux (NFR13.1) e para a hospedagem (NFR10.1) |
| u3-identity-access | 3 | `Deferred`: NFR3.1 e NFR3.2 foram medidos aqui (`Met`, cenário `u3`); NFR3.4 depende do smoke da U3 fora de `scripts/verify.sh` |
| u6-risk-control | 3 | `Deferred`: NFR2.2 e NFR3.2 dependem da carga e do smoke da U5; NFR10.1 depende da hospedagem |
| u7-ledger-pnl | 2 | `Deferred`: NFR3.1 foi medido aqui (`Met`, cenário `u7`); NFR3.4 depende do smoke da U5 |

## Dono, arquivo e correção

| Achado | Dono | Arquivo | Correção |
|---|---|---|---|
| T1. Formato `links` | u8-admin-feedback, u9-dashboard (code-generation) | `construction/u8-admin-feedback/code-generation/traceability.json`, `construction/u9-dashboard/code-generation/traceability.json` | reescrever no formato das demais Units (`stage`, `unit`, `upstream_ids`, `coverage[{id,status,target}]`), sem mudar o conteúdo |
| T2. NFR fora do registro da geração | u4-discovery (code-generation) | `construction/u4-discovery/code-generation/traceability.json` | acrescentar as linhas de NFR com os alvos já citados no `nfr-design/traceability.json` e no código |
| T3. Alvos com texto livre e IDs ausentes | u2-audit-trail (code-generation) | `construction/u2-audit-trail/code-generation/traceability.json` | deixar só caminhos em `target`, mover as notas para o status, e acrescentar NFR2.3, 3.2, 6.8 e 10.1 |
| T4. Itens `Deferred` para este estágio | u3, u5, u6, u7 | matriz em `build-and-test-summary.md` | os medidos aqui ficam `Met` na matriz; os da U5 voltam com a correção da semente (volta 1); os de smoke e de hospedagem continuam pendentes |

## Cobertos

Os demais 180 elementos estão cobertos. A lista completa, com os ACs de cada FR, está na saída do sensor, guardada no momento da execução. Ela é refeita quando o estágio roda de novo depois da volta 1.
