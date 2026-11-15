# Perguntas de NFR Requirements — U1 `u1-bot-wallet`

Conversa: português (Brasil). Base: `construction/u1-bot-wallet/functional-design/functional-spec.md` e `rules.md`, `inception/requirements-analysis/requirements.md` (NFR1 a NFR10), `inception/contract-design/contract-summary.md` (R1, R2, X3, X4, tempos limite de Q9), `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, as regras de `team.md` e `project.md` e o achado da segunda revisão do Functional Design (taxa do saque total).

## Q1. [Segurança] Qual KMS assina Ed25519?

A. AWS KMS com chave Ed25519 (assimétrica, não exportável), uma chave por carteira do bot, na conta AWS nova (TR-C20); a prova técnica do início do B1 confirma que a assinatura sai no formato da Solana; se falhar, o Bolt para e volta ao Domain Design
B. Outro KMS ou HSM
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Stack] Como é o TxSigner?

A. Java 21 com Spring Boot, um módulo Maven próprio (`apps/txsigner`) com o mínimo de dependências, sem o SDK Solana, com Ed25519 e Base58 próprios; esquema e usuário de banco próprios no mesmo PostgreSQL no local e banco separado na nuvem; credencial de serviço em variável de ambiente no local e no gerenciador de segredos na nuvem, girada a cada 90 dias
B. Outra linguagem só para o TxSigner
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Segurança] Como é o KMS simulado no local e nos testes?

A. Um contêiner à parte (`kms-sim`), com código só em `infra/` de teste, que imita a API do KMS para criar chave e assinar; ele nunca entra no artefato de produção do TxSigner, e o perfil de produção do TxSigner recusa subir apontando para ele
B. Uma classe simulada dentro do TxSigner ligada por perfil
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Teste] Como roda a rede no local?

A. `solana-test-validator` num contêiner do Compose de teste, com a carteira principal de teste gerada no código de teste; o `verify.sh` (alterado pelo responsável) sobe o validador junto
B. Devnet pública nos testes
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Desempenho] Metas da U1

A. Desafio e leituras da carteira: p95 até 300 ms; pedido de saque (resposta da API): p95 até 500 ms; do pedido ao envio para a rede: p95 até 5 s; ativação (sem contar o tempo do usuário assinando): p95 até 3 s; depósito visível no saldo até 45 s depois de finalizado
B. Sem metas numéricas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Confiabilidade] Disponibilidade e falhas

A. Meta do piloto: 99,5% ao mês para leitura e saque; com o TxSigner ou o RPC fora do ar, o saque fica pedido e o worker tenta de novo com espera crescente por até 24 h, avisando o usuário; nenhum dado se perde com reinício (estados gravados); backup diário do banco do TxSigner com teste de restauração antes da mainnet
B. Sem meta
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Escala] Quantas carteiras?

A. Piloto com até 21 carteiras; desenho e testes de carga para 1.000 carteiras ativas, com a varredura de depósitos em lotes e no máximo 5 consultas por segundo ao RPC
B. Só o piloto
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Segurança] Limites de uso por usuário

A. Desafios de consentimento: até 10 por minuto; pedidos de saque: até 10 por hora; acima disso, `429 RATE_LIMITED` com o mecanismo existente
B. Sem limites
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Observabilidade] O que medir e alertar na U1

A. Métricas: carteiras ativas, saldo total em carteiras do bot (lamports), depósitos (conhecidos e desconhecidos), saques por estado, tempo do pedido à confirmação, assinaturas e recusas do TxSigner por motivo, chaves órfãs. Alertas: recusa do TxSigner, saque ambíguo há mais de 5 min, depósito de origem desconhecida acima de 0,001 SOL, chave órfã, TxSigner fora do ar por mais de 2 min. Logs sem endereço nem assinatura por extenso
B. Só logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Correção da revisão] Taxa do saque total

A. A taxa é calculada antes de pedir a assinatura (taxa base por assinatura mais a prioridade escolhida, sempre até o teto de taxa) e gravada no saque; o saque total envia exatamente o saldo menos essa taxa, zerando a carteira; o TxSigner confere que a taxa calculada é a da transação; a reserva continua sendo valor mais teto de taxa; o blockhash e o resumo da mensagem são gravados antes do pedido de assinatura; a recusa por resumo diferente leva o saque a FAILED (a primeira transação nunca foi enviada); um desafio IN_USE volta a PENDING depois de 30 s sem resposta
B. Manter o teto de taxa no cálculo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- AWS KMS Ed25519, uma chave por carteira, confirmado pela prova técnica [Q1].
- TxSigner em Java 21 e Spring Boot, módulo próprio, sem SDK Solana, banco e credencial próprios com giro de 90 dias [Q2].
- KMS simulado em contêiner só de teste, recusado pelo perfil de produção [Q3].
- Validador Solana local no Compose de teste [Q4].
- Metas: leituras 300 ms, saque 500 ms e envio 5 s, ativação 3 s, depósito 45 s (p95) [Q5].
- 99,5% ao mês no piloto; saque tentado de novo até 24 h; backup diário do TxSigner com restauração testada [Q6].
- 21 carteiras no piloto, desenho para 1.000, RPC até 5 consultas por segundo [Q7].
- 10 desafios por minuto e 10 saques por hora por usuário [Q8].
- Métricas e alertas da carteira e do TxSigner; logs mascarados [Q9].
- Taxa do saque calculada antes de assinar; saque total zera a carteira; ajustes de blockhash, recusa e desafio preso [Q10].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
