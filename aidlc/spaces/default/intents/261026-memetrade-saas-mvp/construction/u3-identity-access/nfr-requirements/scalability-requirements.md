# Requisitos de escalabilidade — U3 Convites, contas e sessões

Requisitos de carga e crescimento da Unit `u3-identity-access`, derivados do NFR2 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e do que ficou decidido sem pergunta em [nfr-requirements-questions.md](nfr-requirements-questions.md). A U3 não tem meta de escala própria: o beta tem 20 convidados.

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR2.1 | Volume de identidades do beta | O desenho e os testes consideram até 20 convidados ativos, 1 administrador e até 200 convites guardados; nenhuma consulta da U3 percorre mais que uma página de convites (até 100) | NFR2, Q3 (todos os convites guardados) |
| NFR2.2 | Lista de convites paginada | `GET /api/v1/admin/invites` usa cursor, com `limit` padrão de 20 e máximo de 100; `limit` fora do intervalo responde `400 VALIDATION_FAILED`. Índice por `createdAt` decrescente e `inviteId` | BR1.3, C8 |
| NFR2.3 | Uma instância da API no beta | Os contadores do limite de consultas do convite ficam na memória do processo, como os da entrada da U1. Com mais de uma instância, o limite passa a valer por instância; essa troca é revista com a hospedagem, antes de escalar | NFR2, U1 (Q2 de NFR) |
| NFR2.4 | Contagens sem varredura cara | `IdentityAdministration.counts()` responde com consultas agregadas indexadas por papel, status e validade; p95 ≤ 100 ms com o volume do NFR2.1 | BR1.6 |
