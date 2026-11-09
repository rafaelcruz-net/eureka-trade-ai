# Mapa de histórias por Unit — Termos de uso e LGPD

Conversa: português (Brasil). Fontes: `inception/user-stories/stories.md`, `inception/domain-design/components.md`, `decisions.md`, `inception/requirements-analysis/requirements.md` e `units-generation-questions.md`.

## Mapa

| Story | Unit ID | Directory |
|---|---|---|
| US1.1 | U1 | u1-legal-documents |
| US1.2 | U1 | u1-legal-documents |
| US1.3 | U1 | u1-legal-documents |
| US1.4 | U2 | u2-retention |
| US2.1 | U1 | u1-legal-documents |
| US2.2 | U1 | u1-legal-documents |
| US2.3 | U3 | u3-reacceptance |
| US2.4 | U1 | u1-legal-documents |
| US2.5 | U1 | u1-legal-documents |
| US2.6 | U1 | u1-legal-documents |
| US3.1 | U2 | u2-retention |
| US3.2 | U2 | u2-retention |
| US4.1 | U3 | u3-reacceptance |
| US4.2 | U3 | u3-reacceptance |
| US5.1 | U4 | u4-data-requests |
| US5.2 | U4 | u4-data-requests |
| US5.3 | U4 | u4-data-requests |
| US5.4 | U4 | u4-data-requests |
| US5.5 | U4 | u4-data-requests |
| US5.6 | U4 | u4-data-requests |
| US5.7 | U4 | u4-data-requests |
| US5.8 | U4 | u4-data-requests |
| US5.9 | U4 | u4-data-requests |
| US6.1 | U5 | u5-anonymization |
| US6.2 | U5 | u5-anonymization |
| US6.3 | U5 | u5-anonymization |

## Transversais

- NFR1 (nada de dado pessoal em log) e NFR2 (isolamento) valem em U2, U3, U4 e U5; cada Unit traz os próprios testes.
- NFR4 (acessibilidade) vale em todas as telas: U1, U3, U4 e U5.

## Ordem dentro de cada Unit

- **U1:** US1.2 → US1.1 → US1.3 → US2.1 → US2.2 → US2.4 → US2.5 → US2.6.
- **U2:** US3.2 → US3.1 → US1.4.
- **U3:** US4.1 → US4.2 → US2.3.
- **U4:** US5.1 → US5.2 → US5.3 → US5.5 → US5.4 → US5.6 → US5.7 → US5.8 → US5.9.
- **U5:** US6.2 → US6.1 → US6.3.

## Verificação de cobertura

As 26 histórias estão atribuídas, e cada Unit tem histórias: U1 com 8, U2 com 3, U3 com 3, U4 com 9 e U5 com 3.
