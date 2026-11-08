# Rastreabilidade entre Units

Conversa: português (Brasil). Este arquivo foi gerado com `aidlc engine sensor-traceability --stage build-and-test` em 2026-11-08, na quarta execução, depois da volta 3.

## Veredito

**Reprovado.** De 215 elementos (FR, AC e grupos de NFR), 199 estão cobertos e 16 não. Na primeira execução eram 180 cobertos e 35 não; a segunda, a terceira e esta deram o mesmo número.

A volta 1 resolveu os achados de formato T1, T2 e T3. Agora o leitor aceita o arquivo de todas as Units. Continuam descobertos os itens abaixo, e cada um é um achado deste estágio, levado à aprovação.

## Elementos não cobertos

| ID | Unit dona | Por que aparece sem cobertura |
|---|---|---|
| FR14.1–FR14.4, FR15.1 | nenhuma | nenhum AC chega a esses FRs em nenhum registro de rastreabilidade. Eles vêm de `inception/requirements-analysis/requirements.md`, e nenhuma história do beta os detalha. É lacuna de rastreio entre requisitos e histórias, não de código; o dono é a Inception (User Stories e Units Generation) |
| NFR1, NFR2, NFR3, NFR5, NFR6, NFR7, NFR9, NFR10, NFR12, NFR13, NFR14 (grupos) | várias | um grupo reprova quando algum NFR de alguma Unit está `Deferred` ou `N/A` no registro da geração de código. O detalhe está abaixo |

## NFR não construídos, por Unit

| Unit | IDs | Motivo |
|---|---|---|
| u4-discovery | NFR1.2, 1.3, 2.1, 2.3, 2.5, 3.3, 3.5, 5.1, 6.8, 7.6, 9.2, 9.3, 9.4, 9.8, 10.5, 14.1 | `Deferred` na volta 1, com motivo registrado. NFR3.5 está reprovado: é a decisão de escala antes do G1. NFR6.8, NFR9.8 e NFR14.1 são lacunas reais anotadas no resumo da U4 |
| u5-bots-decision | NFR2.1, 2.5, 2.6, 3.1, 3.2, 10.1, 13.1 | `Deferred` no registro da geração de código para a carga `u5`, a hospedagem e a CI. Na matriz, NFR2.1, 2.5, 2.6, 3.1 e 3.2 estão `Met` (carga `u5` da volta 2); NFR2.2 e 2.3 saíram desta lista e estão `OK` desde a volta 2 |
| u6-risk-control | NFR2.2, 3.2, 10.1 | `Deferred` no registro. Na matriz, NFR2.2 está `Met` (mesma medida do U5-NFR2.3, carga `u5` da volta 2); NFR3.2 é a tela da U5; NFR10.1 depende da hospedagem |
| u3-identity-access | NFR3.1, 3.2, 3.4 | `Deferred`. NFR3.1 e 3.2 estão `Met` na matriz (cenário `u3`); NFR3.4 depende do smoke da U3 fora de `scripts/verify.sh` |
| u7-ledger-pnl | NFR3.1, 3.4 | `Deferred`. NFR3.1 está `Met` na matriz (cenário `u7`); NFR3.4 depende do smoke da U5 |
| u8-admin-feedback | NFR6.7, 12.1, 13.1 | `Deferred` para o smoke, a cobertura do backend e a CI no Linux |

## Dono e encaminhamento

| Achado | Dono | Encaminhamento |
|---|---|---|
| FR14.x e FR15.1 sem AC | Inception (User Stories, Units Generation) | decisão da pessoa: ligar esses FRs a histórias e Units ou marcá-los fora do beta |
| U5 NFR2.2 e 2.3, U6 NFR2.2 | u5-bots-decision, u6-risk-control (code-generation) | resolvidos na volta 2; carga `u5` aprovada |
| Itens `Deferred` de smoke, hospedagem e CI | pessoa, CI Pipeline e estágios de infraestrutura | continuam pendentes, como na primeira execução |
| U4 NFR3.5 | pessoa | decisão de escala antes do G1 |

## Cobertos

Os outros 199 elementos estão cobertos. A lista completa, com os ACs de cada FR, está na saída do sensor desta execução, que é refeita a cada vez que o estágio roda.
