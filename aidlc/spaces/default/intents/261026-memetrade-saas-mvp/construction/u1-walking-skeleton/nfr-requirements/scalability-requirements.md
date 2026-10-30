# Requisitos de escala — U1 Esqueleto de ponta a ponta

Capacidade da Unit `u1-walking-skeleton` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) e as regras de [rules.md](../functional-design/rules.md), a partir do NFR2 e do NFR3 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e da topologia de processos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (C20). Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q1, Q2).

## Requisitos

| ID | Requisito | Meta | Mecanismo | Origem |
|---|---|---|---|---|
| NFR3.3 | Capacidade do beta com uma única instância de API | 20 usuários simultâneos com as metas de NFR3.1 e NFR3.2 | Um processo de API, banco PostgreSQL local, consultas paginadas por cursor | NFR3, Q1 |
| NFR3.4 | Volume de dados de mercado simulados | Até 500 tokens com 5 campos cada, sem perder as metas de NFR3.1 | Uma observação vigente por token, campo e fonte; índice pela ordem da lista | NFR3 |
| NFR3.5 | Contadores do limite de tentativas válidos com uma instância | Contadores na memória do processo de API; ao passar a mais de uma instância, migrar para um armazenamento compartilhado (Redis) antes de subir a segunda | Contador por janela de 1 minuto | Q2 |

## Plano de capacidade

| Dimensão | Beta local (U1) | Antes do beta multiusuário | Mecanismo de escala |
|---|---|---|---|
| Usuários simultâneos | 20 | Revisto após o discovery | Mais instâncias de API sem estado de sessão local (a sessão está no banco) |
| Instâncias de API | 1 | 2 ou mais | Contadores do limite de tentativas no Redis (NFR3.5) |
| Tokens de mercado | Até 500 simulados | Depende das fontes reais (SP-1) | Retenção de 24 h e índices (ADR-008) |
| Eventos de auditoria | Dezenas por minuto | Revisto no NFR Design | Relay em lotes da tabela de saída |

## Degradação

Quando a capacidade chega ao limite, a API responde `503` com `ProblemDetail` em vez de aceitar requisições que não consegue servir no prazo; o login e a lista degradam juntos, porque compartilham o processo.

## Fora desta Unit

- NFR2 (60 bots ativos): sem bots na U1; tratado na U5.
