# Requisitos de confiabilidade — U6 Barreira de limites

Requisitos de comportamento em falha da Unit `u6-risk-control`. As fontes são o NFR4, o NFR8 e o NFR10 de [requirements.md](../../../inception/requirements-analysis/requirements.md), as regras de [rules.md](../functional-design/rules.md), as regras da C14 e da C15 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e a resposta Q5 de [nfr-requirements-questions.md](nfr-requirements-questions.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR10.1 | Sem meta de disponibilidade no beta local | Nenhum SLO numérico; a meta vem junto com a hospedagem | NFR10 |
| NFR10.2 | Falha fechada | Qualquer falha técnica na avaliação (C11, C13, banco, tempo de espera) lança exceção, desfaz a transação inteira do ciclo e nenhuma ordem é executada. Nunca há aprovação por padrão. Teste com falha injetada em cada leitura e em cada gravação | C14, C15, ADR-003 |
| NFR10.3 | Dado ausente recusa | Preço ou liquidez `UNKNOWN` ou com mais de 60 s recusam a ordem; patrimônio vazio recusa por drawdown. A barreira nunca aprova sem os dados que a verificação exige | BR2.5, BR2.6, BR2.11, BR3.2 |
| NFR10.4 | Sem nova tentativa dentro do ciclo | A U6 não repete leituras nem gravações; o próximo ciclo da U5 decide de novo | C15 (regras) |
| NFR10.5 | Sem estado em memória | Nenhum cache de limites, dados de mercado ou visão de risco; um reinício do processo não muda nenhum resultado | BR1.3 |
| NFR10.6 | Expurgo seguro | A tarefa de expurgo roda uma vez por dia, com execução única pelo ShedLock (padrão da U2 e da U4), é idempotente e só apaga aprovadas com mais de 30 dias sem recusa que as referencie. Falha numa rodada não apaga nada pela metade (lote numa transação) e é repetida na rodada seguinte | Q5, NFR4.3 |
| NFR10.7 | Duplicidade depois do expurgo | Uma decisão com mais de 30 dias avaliada de novo não é reconhecida como repetida pela U6. A segunda execução continua contida pela unicidade da chave de idempotência da U7. Registrado como limite conhecido | Q5, AC4.6.3 |
| NFR4.6 | Banco consistente com o resultado | Avaliação, recusa, detalhes e evento da trilha são gravados juntos ou nada, na transação do ciclo; teste com falha injetada depois de cada gravação | BR3.4, BR3.5, C9 |
| NFR8.2 | Recálculo determinístico | Mesma entrada (ordem, limites, dados de mercado, visão de risco, relógio) dá o mesmo resultado; relógio injetável e nenhuma aleatoriedade na barreira | NFR8, `team.md` |

## Recuperação

- **Backup.** O banco segue o backup do ambiente, como as outras Units; a U6 não tem armazenamento próprio fora do PostgreSQL.
- **Reprocessamento.** Uma recusa nunca é refeita nem apagada. Um defeito na regra é corrigido no código, e as recusas antigas continuam como registro do que aconteceu.
