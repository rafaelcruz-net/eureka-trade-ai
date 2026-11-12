# Rastreabilidade final entre Units

Conversa: português (Brasil). Gerada em 2026-11-11 a partir de `aidlc engine sensor-traceability --stage build-and-test`, sobre os `traceability.json` da geração de código das Units U1 a U5 do intent `261109-termos-lgpd`.

## Veredito

**Não passou (pass: false)**: de 38 requisitos de nível superior, 37 estão construídos. O único descoberto é **NFR8** (observabilidade), por dois itens que a geração de código deixou, com motivo, para um estágio posterior deste plano:

| ID | Unit | Estado | Dono | Motivo |
|---|---|---|---|---|
| NFR8.5 | `u2-retention` | Deferred | `observability-setup` | provisionar o alarme `PURGE_STALE` no painel e nos alarmes; a linha do alerta já está em `u2-retention/infrastructure-design/monitoring-design.md` e os indicadores de saúde estão testados |
| NFR8.3 | `u4-data-requests` | Deferred | `observability-setup` | provisionar o alarme `DATA_REQUEST_OVERDUE`; a linha está em `u4-data-requests/infrastructure-design/monitoring-design.md` e o gauge `memetrade_data_requests_overdue` está testado (`DataRequestMetricsTest`) |

Nenhum dos dois é código faltando: o que falta é o alarme provisionado, que depende de um ambiente com monitoramento (Observability Setup, mais adiante neste plano).

## Ajustes feitos nesta execução

- **U5:** vários alvos juntavam dois arquivos numa linha (com `;` ou nota entre parênteses), e a ferramenta procurava um arquivo com o nome inteiro. Cada alvo passou a apontar um único arquivo existente.
- **U2, U3 e U4:** as metas de carga que a geração de código tinha deixado para este estágio (`U2 NFR3.4`, `U3 AC4.2.4`, `BR3.11`, `NFR3.1`, `NFR3.3`, `U4 AC5.1.5`, `BR4.19`, `NFR3.1` a `NFR3.3`) foram medidas aqui e passaram (ver `test-results.md`); passaram a apontar o script do cenário (`scripts/load/load-test.mjs`, `reacceptance.mjs`, `legal.mjs`, `data-requests.mjs`).
- **U3 NFR3.2:** depois da volta à geração de código, aponta para `TermsReacceptanceWarmupIT.java`.

## Cobertura por requisito

| Requisito | Construído | Por meio de |
|---|---|---|
| FR1.1 a FR1.4, FR3.4 | sim | AC1.1.x a AC1.4.x (U1, U2) |
| FR2.1 a FR2.4, FR8.1 | sim | AC2.1.x a AC2.6.1 (U1, U3) |
| FR3.1 a FR3.3 | sim | AC3.1.x, AC3.2.x (U2) |
| FR4.1 a FR4.4 | sim | AC4.1.x, AC4.2.x (U3) |
| FR5.1 a FR5.7, FR7.1 | sim | AC5.1.x a AC5.9.x (U4) |
| FR6.1 a FR6.4 | sim | AC6.1.x a AC6.3.x (U5) |
| NFR1 a NFR7, NFR9 | sim | itens das cinco Units |
| NFR8 | **não** | NFR8.5 (U2) e NFR8.3 (U4), donos: `observability-setup` |

As 87 histórias de aceitação (AC) estão todas construídas, cada uma com o arquivo de teste que a cobre listado no `traceability.json` da Unit dona.
