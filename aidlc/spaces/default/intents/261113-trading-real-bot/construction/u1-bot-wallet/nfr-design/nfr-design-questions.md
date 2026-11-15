# Perguntas de NFR Design — U1 `u1-bot-wallet`

Conversa: português (Brasil). Base: `construction/u1-bot-wallet/nfr-requirements/` (os seis documentos), `construction/u1-bot-wallet/functional-design/functional-spec.md`, `inception/contract-design/contract-summary.md`, `inception/domain-design/components.md`, `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md` (Resilience4j, ShedLock, Micrometer e Prometheus, Liquibase) e os cinco pontos menores da segunda revisão do NFR Requirements.

## Q1. [Resiliência] Como proteger as chamadas ao TxSigner, ao RPC e ao KMS?

A. Resilience4j, que já está no projeto, acrescentando o circuit breaker: tempo limite e uma repetição como no contrato. O circuito abre com 50% de falhas em 20 chamadas e fica aberto por 30 s. Com o circuito aberto, a operação fica pendente e é tentada de novo pelo worker, nunca recusada ao usuário. Há um circuito por provedor de RPC, com troca automática para o segundo
B. Só tempo limite e repetição
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Trabalhos] Como rodam a varredura de depósitos, o acompanhamento dos saques e a verificação de órfãs?

A. Jobs agendados no worker com o ShedLock, que já existe, para garantir uma execução por vez. Os saques são pegos com trava `SKIP LOCKED`, por lotes de 20. A varredura de depósitos roda a cada 15 s, o acompanhamento de saques a cada 2 s e a verificação de órfãs uma vez por dia
B. Uma fila de mensagens nova
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [RPC] Como dividir o limite de consultas ao RPC?

A. O limite `rpcMaxRps` é por provedor. 20% fica reservado para saques e confirmações, e a varredura usa no máximo 80%. Quando o primeiro provedor está no limite ou fora do ar, o segundo assume com o mesmo limite
B. Sem reserva
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Recuperação] Carteira criada dentro da janela de perda de dados (RPO) do TxSigner restaurado

A. A API guarda a mensagem e a assinatura completas de cada consentimento, além do resumo. Em modo de recuperação, o TxSigner aceita reconstruir uma WalletPolicy só quando: confere de novo a assinatura da carteira principal no consentimento de ativação e nos seguintes; encontra no KMS a chave com a etiqueta da aplicação cujo endereço público é o da carteira; e aplica os valores mais restritos entre os consentimentos e os rebaixamentos. Consentimentos vencidos valem só para essa reconstrução, nunca para ampliar a política
B. Recriar as carteiras do zero
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Disponibilidade] O RTO de 4 h e a meta de 99,5%

A. Deixar escrito que uma restauração completa do banco é um desastre que pode estourar o orçamento de erro do mês. O SLI continua medindo as respostas 5xx, inclusive nesse período, e um desastre leva a uma revisão pós-incidente. O objetivo de ensaio é restaurar em até 2 h
B. Tirar o período de restauração do SLI
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Segurança] Endpoint de VPC do KMS e criação de chaves

A. O perfil de produção aceita o endereço padrão do KMS e os endpoints de VPC da própria conta, listados na configuração. Qualquer outro endereço impede o TxSigner de subir. A política do papel exige a etiqueta da aplicação no CreateKey (`aws:RequestTag`) e no Sign (`aws:ResourceTag`). O limite de 10 chaves por hora fica na aplicação, com alarme do CloudTrail acima de 10 por hora na conta. O contrato X4 é atualizado no Infrastructure Design para dizer que o KMS simulado é um contêiner
B. Só o endereço padrão
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Desempenho] Cache e consultas

A. Sem cache para saldo ou estado de saque, que são lidos sempre do banco. Índices por carteira e tipo nos lançamentos REAL, e por estado nos saques. O saldo é calculado por soma agregada numa consulta. Pool de conexões separado para a API e o worker, como hoje. A chave pública de cada carteira fica em cache na memória do TxSigner
B. Cache de saldo com expiração curta
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Observabilidade] Ferramentas

A. Micrometer e Prometheus, que já existem, nos três processos (API, worker e TxSigner). Logs estruturados em JSON com `correlationId` propagado no cabeçalho até o TxSigner. Um painel "Carteiras REAL" com as métricas da U1. Os alertas ficam em regras versionadas, ativadas no Observability Setup
B. Outra ferramenta
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Resilience4j com circuit breaker por TxSigner, provedor de RPC e KMS; circuito aberto deixa a operação pendente [Q1].
- Jobs no worker com ShedLock e SKIP LOCKED: depósitos 15 s, saques 2 s, órfãs diária [Q2].
- Limite de RPC por provedor, 20% reservado para saques e confirmações [Q3].
- Reconstrução da WalletPolicy na recuperação só com consentimentos completos conferidos de novo e a chave do KMS [Q4].
- Restauração completa é desastre que pode estourar o SLO; ensaio em 2 h [Q5].
- Endpoints de VPC da própria conta aceitos; etiqueta obrigatória no CreateKey e no Sign; alarme acima de 10 chaves por hora; X4 atualizado no Infrastructure Design [Q6].
- Sem cache de saldo; índices; cache só da chave pública no TxSigner [Q7].
- Micrometer, Prometheus, logs JSON com correlationId e painel "Carteiras REAL" [Q8].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
