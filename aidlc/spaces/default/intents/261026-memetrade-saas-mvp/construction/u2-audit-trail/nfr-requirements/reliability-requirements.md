# Requisitos de confiabilidade — U2 Trilha de auditoria

Confiabilidade da Unit `u2-audit-trail` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) (F1, F2, F4, F5) e as regras de [rules.md](../functional-design/rules.md) (BR1.1, BR3.2, BR3.3, BR3.4), a partir do NFR4, NFR10 e NFR13 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e do contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q3, Q4, Q5).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR4.1 | Exatamente um evento por ação sensível | Testes de integração: ação confirmada gera um evento; ação desfeita não gera nenhum; o mesmo `eventId` publicado duas vezes gera uma entrada e um evento; processo derrubado depois do commit entrega o evento uma única vez ao voltar (mantido da U1) | BR1.1, FR2.3, NFR4 |
| NFR4.2 | Entrega rápida à trilha | 99% dos eventos com `recordedAt` até 5 s depois da criação da entrada na tabela de saída, numa janela de 1 hora com a carga de NFR2.1 e picos de NFR2.3, no ambiente local | Q3, C9 |
| NFR4.3 | Nenhuma entrada pendente apagada | Teste: entradas pendentes com mais de 7 dias continuam depois da limpeza; só entregues há mais de 7 dias saem | BR3.4 |
| NFR4.4 | Falha do expurgo ou da limpeza sem perda e com sinal | Falha de um lote para a execução, mantém o que já foi apagado e a próxima execução continua; o expurgo grava `AUDIT_RETENTION_PURGED` com o resultado sempre que o banco aceita a gravação, e registra log `ERROR` quando não aceita; a saúde da trilha fica degradada até a próxima execução bem-sucedida | BR3.2, BR3.3, Q4 |
| NFR4.5 | Agenda com recuperação depois de máquina desligada | Expurgo às 03:00 UTC e limpeza às 03:30 UTC, cada execução com limite de 30 minutos; ao subir o processo, cada tarefa roda logo se a última execução bem-sucedida tiver mais de 24 h; o horário da última execução bem-sucedida de cada tarefa sobrevive a reinícios; saúde degradada quando a última execução falhou ou parou pelo limite de tempo, ou quando a última bem-sucedida tem mais de 26 h. Testes com relógio injetável | Q4 |
| NFR4.6 | Uma execução por vez | Duas instâncias (ou duas chamadas simultâneas) disparando o expurgo ao mesmo tempo resultam em uma única execução; a outra termina sem apagar nada e sem erro; o mesmo para a limpeza | BR3.2, Q5 |
| NFR4.7 | Ação sensível não confirma sem o seu evento | Se a gravação na tabela de saída falha, a ação sensível desfaz junto (o publicador exige a transação da ação); relay, expurgo ou consulta indisponíveis não bloqueiam ações de negócio | C9, FR2.3 |
| NFR10.1 | Sem meta de disponibilidade enquanto o beta roda só localmente | Nenhum SLO de disponibilidade na U2; a meta numérica é definida junto com a hospedagem, antes dos convites. A meta de entrega NFR4.2 é de integridade, não de disponibilidade | NFR10 |
| NFR13.1 | Agenda independente do fuso do computador | Horários das tarefas e o corte de 12 meses calculados em UTC, com o mesmo resultado no Windows local (fuso de Brasília) e na CI em Linux; testes com relógio injetável nas fronteiras do dia UTC | NFR13, BR3.1 |

## Recuperação

| Objetivo | Beta local (U2) | Antes do beta multiusuário |
|---|---|---|
| RTO | Subir de novo com o script de verificação; tarefas atrasadas rodam ao subir (NFR4.5) | Definido com a hospedagem |
| RPO | Sem backup local; o volume do banco persiste entre reinícios; eventos confirmados nunca se perdem por queda do processo (NFR4.1) | Backups criptografados com restauração testada, retenção alinhada à da trilha (NFR7.6) |
| Rollback | Voltar ao commit anterior e subir de novo; migrações em expand-contract; as migrações de papéis e gatilho da U2 são aditivas | Caminho de rollback documentado e testado (`team.md`) |

## Saúde da trilha

| Indicador | Saudável | Degradada |
|---|---|---|
| Relay (U1) | Pendência mais antiga abaixo de 60 s | 60 s ou mais |
| Expurgo | Última execução bem-sucedida há até 26 h e a última não falhou | Última falhou ou parou pelo limite, ou a última bem-sucedida tem mais de 26 h |
| Limpeza da tabela de saída | Idem | Idem |

A saúde degradada da trilha não derruba a API; banco indisponível a deixa `DOWN`, como na U1.
