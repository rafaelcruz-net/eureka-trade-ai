# Requisitos de confiabilidade — U2 `u2-retention`

Conversa: português (Brasil). Fontes: `construction/u2-retention/functional-design/` (BR2.4, BR2.7, BR2.8, BR2.10, BR2.16), `inception/requirements-analysis/requirements.md` (NFR5, NFR7, NFR9) e `nfr-requirements-questions.md` (Q5).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR7.1 | Os dois expurgos novos rodam uma vez por dia (desafios às 03:20 UTC, sessões às 03:40 UTC), em lotes de até 1000, com uma transação por lote. Numa falha, os lotes já feitos ficam e a próxima execução tenta de novo | teste de integração com falha injetada no segundo lote |
| NFR7.2 | Os indicadores `loginChallengePurge` e `sessionPurge` ficam `UP` até 48 h depois da subida, ou com último sucesso há 48 h ou menos. Fora disso, ficam `DEGRADED`. Eles ficam fora dos grupos de liveness e readiness | teste de unidade do indicador com relógio injetável |
| NFR7.3 | Quando a API fica pronta, ela roda pela mesma trava cada expurgo novo sem linha, sem sucesso ou com último sucesso há mais de 24 h | teste de integração da recuperação |
| NFR7.4 | Uma falha de expurgo nunca derruba a API nem o login | teste de integração com banco indisponível no expurgo |
| NFR7.5 | Sem a chave HMAC válida, a API e o worker falham rápido na subida, com a configuração que falta e sem mostrar valor | teste de configuração |
| NFR5.1 | A trilha continua append-only. A migração da Unit só acrescenta, e nenhuma linha existente é alterada. Ela traz: a coluna `wallet_hmac` com o índice dela em `audit_event`, a tabela de execuções do Identity e o índice de fim de sessão em `user_session` (NFR3.5) | a migração não tem `UPDATE`, `DELETE` nem `DROP` (teste que lê o arquivo da migração); o teste existente de UPDATE e DELETE recusados na trilha continua verde |
| NFR9.1 | Os expurgos são lógica de dados pessoais. Os testes das regras BR2.5, BR2.6 e BR2.8 são escritos antes do código, com relógio injetável, e o teste de conformidade (BR2.10 a BR2.12) entra na suíte. O piso é de 80% de linhas | revisão da ordem no plano de código; JaCoCo |

## Degradação

Se um expurgo falhar, os dados vencidos ficam mais um dia e a saúde mostra o atraso depois de 48 h. O serviço continua normal. A política nunca promete um prazo que o código não aplica: o teste de conformidade falha antes do merge.
