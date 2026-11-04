# Requisitos de confiabilidade — U3 Convites, contas e sessões

Requisitos de integridade, tolerância a falhas e recuperação da Unit `u3-identity-access`, derivados do NFR4 e NFR10 de [requirements.md](../../../inception/requirements-analysis/requirements.md), das regras BR1.4, BR1.7, BR1.8 e BR3.2 a BR3.7 de [rules.md](../functional-design/rules.md) e do fluxo F5 de [functional-spec.md](../functional-design/functional-spec.md).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR4.1 | Criação da conta tudo ou nada | Pessoa, carteira, convite usado, aceite, evento e sessão são gravados numa única transação. Teste com falha injetada em cada gravação (banco recusa a inserção do aceite, do evento e da sessão): nenhuma linha nova fica, o convite continua `PENDING` e a resposta é 5xx | BR3.3, AC1.1.3 |
| NFR4.2 | Um convite, uma conta | Teste de concorrência com duas verificações simultâneas do mesmo convite (barreira de início comum, repetido 50 vezes): sempre exatamente uma conta; a outra recebe `422 INVITE_INVALID` com `reason: USED` | BR3.4, AC1.1.4 |
| NFR4.3 | Uma carteira, uma conta | Teste de concorrência com a mesma carteira e dois convites diferentes ao mesmo tempo (repetido 50 vezes): sempre uma conta; a outra recebe `409 WALLET_ALREADY_LINKED` e seu convite continua `PENDING` | BR3.7 |
| NFR4.4 | Revogação e aceite ordenados | Teste de concorrência entre revogação e verificação do mesmo convite: ou a conta é criada e a revogação recebe `409 INVITE_NOT_REVOCABLE`, ou o convite fica `REVOKED` e a verificação recebe `422 INVITE_INVALID` com `reason: REVOKED`; nunca as duas coisas | BR1.4, AC7.1.3 |
| NFR4.5 | Emissão e revogação idempotentes | 100% das emissões e revogações exigem chave de idempotência; repetir com a mesma chave não cria outro convite nem outro evento; o registro de idempotência é gravado na mesma transação do convite | NFR4, BR1.8, C8 |
| NFR10.1 | Sem meta numérica de disponibilidade no beta local | A meta é definida com a hospedagem, antes dos convites, como no NFR10; a U3 cumpre os requisitos de falha abaixo | NFR10 |
| NFR10.2 | Falha técnica sem estado parcial e sem repetição automática | Falha de banco ou tempo esgotado na verificação desfaz tudo e responde 5xx; a API não repete a verificação sozinha (não é idempotente); a tela pede um novo desafio para a pessoa tentar de novo | BR3.6, `team.md` (erros técnicos) |
| NFR10.3 | Termos carregados ou a API não sobe | Na subida, a API lê o arquivo dos termos vigentes; arquivo ausente, sem versão vigente única ou com texto vazio impede a subida com mensagem clara no log. Teste de contexto com arquivo inválido | BR3.1, Q6 do design funcional |
| NFR10.4 | Semeadura do tenant repetível | A migração que semeia o tenant `Beta` pode rodar de novo sem duplicar nem alterar o tenant; teste aplica as migrações duas vezes | BR1.7 |
| NFR10.5 | Reinício sem perder dados | Convites, contas, aceites e sessões sobrevivem a um reinício da API; os contadores do limite de consultas recomeçam do zero, o que é aceito no beta | NFR2.3 (escalabilidade) |
| NFR10.6 | Validade exata no limite | Com relógio injetável, o convite é aceito em `expiresAt` e recusado em `expiresAt + 1 s`; a emissão aceita exatamente 1 hora e 30 dias e recusa 1 segundo a menos ou a mais | BR1.1, BR1.2, AC1.1.2 |
