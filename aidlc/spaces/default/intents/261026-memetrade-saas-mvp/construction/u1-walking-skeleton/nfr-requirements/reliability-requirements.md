# Requisitos de confiabilidade — U1 Esqueleto de ponta a ponta

Confiabilidade da Unit `u1-walking-skeleton` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) (F5 coleta, F6 auditoria, F7 verificação) e as regras de [rules.md](../functional-design/rules.md) (BR3.3, BR4.1), a partir do NFR1, NFR4, NFR10 e NFR13 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e do contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR10.1 | Sem meta de disponibilidade enquanto o beta roda só localmente | Nenhum SLO de disponibilidade na U1; a meta numérica é definida junto com a hospedagem, antes dos convites | NFR10 |
| NFR4.2 | Nenhum evento de auditoria perdido | Teste de integração: derrubar o processo depois do commit da ação e conferir que o evento chega à trilha uma única vez quando o relay volta (C9, BR4.1) | NFR4, FR2.3 |
| NFR1.3 | Falha da fonte simulada sem dado falso | Ao falhar a coleta, a fonte vai para `FAILED`, as últimas observações ficam com seus horários e passam a desatualizadas após 5 minutos; nenhum valor vira zero (BR3.1, BR3.4) | NFR1, FR4.4 |
| NFR13.1 | O ambiente sobe com um comando no Windows | O script de verificação sobe o Docker Compose, espera serviços saudáveis e a primeira coleta (limite de 60 s), roda o smoke e devolve código diferente de zero em qualquer falha | NFR13 |
| NFR13.2 | Mesmo resultado no Windows e na CI em Linux | Fim de linha LF por `.gitattributes` e `.editorconfig`; a suíte e o smoke passam nos dois ambientes | NFR13 |
| NFR12.3 | Testes determinísticos | Relógio injetável, semente fixa no gerador simulado, nenhuma rede real; teste instável tratado como defeito | `team.md` |

## Recuperação

| Objetivo | Beta local (U1) | Antes do beta multiusuário |
|---|---|---|
| RTO | Subir de novo com o script de verificação | Definido com a hospedagem |
| RPO | Sem backup local; o volume do banco persiste entre reinícios | Backups criptografados com restauração testada |
| Rollback | Voltar ao commit anterior e subir de novo; migrações em expand-contract | Caminho de rollback documentado e testado (`team.md`) |

## Saúde dos serviços

- API, app do convidado e banco expõem verificação de saúde usada pelo Docker Compose e pelo script de verificação.
- O processo de API só fica saudável depois de aplicar as migrações e conectar ao banco.
