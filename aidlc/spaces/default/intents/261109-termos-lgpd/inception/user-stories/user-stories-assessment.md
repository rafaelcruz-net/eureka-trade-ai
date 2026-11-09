# Avaliação das histórias de usuário — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md`, `aidlc/spaces/default/codekb/eureka-trader-ai/business-overview.md`, `component-inventory.md` e `inception/practices-discovery/team-practices.md`.

## Decisão

**Executar.**

## Motivo

O trabalho tem funções voltadas para pessoas em dois perfis: o convidado, que é titular dos dados, e o administrador, que é o controlador. Há fluxos com regras de negócio sensíveis: novo aceite, pedidos com prazo e anonimização sem quebrar o ledger. As histórias com critérios Given/When/Then dão o teste de aceite de cada fluxo.

## Fatores considerados

| Fator | Situação |
|---|---|
| Tipo de projeto | Brownfield, feature sobre o beta existente |
| Funções para pessoas | Sim: páginas públicas, convite, Conta, administrador |
| Mais de um perfil | Sim: convidado e administrador (mais o visitante sem login) |
| Lógica complexa | Sim: versão e novo aceite, prazo de 15 dias, anonimização |

## Onde as histórias agregam mais

Novo aceite (FR4), pedidos de titular e resposta (FR5), anonimização (FR6) e páginas públicas (FR2).
