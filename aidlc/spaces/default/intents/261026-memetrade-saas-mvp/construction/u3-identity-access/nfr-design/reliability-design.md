# Desenho de confiabilidade — U3 Convites, contas e sessões

Como a Unit `u3-identity-access` atende a [reliability-requirements.md](../nfr-requirements/reliability-requirements.md) (NFR4.1 a NFR4.5 e NFR10.1 a NFR10.6 da U3).

## Concorrência e integridade

| Situação | Mecanismo | Resultado | Requisito |
|---|---|---|---|
| Criação da conta | Uma transação para desafio, convite, pessoa, carteira, aceite, evento e sessão | Tudo ou nada | NFR4.1 |
| Dois usos do mesmo convite | `SELECT … FOR UPDATE` na linha do convite antes de ler o estado | A segunda lê `USED` e recebe `422 INVITE_INVALID` com `reason: USED` | NFR4.2 |
| Mesma carteira com dois convites | Índice único em `wallet.address`; a violação é capturada pelo nome da restrição | Transação desfeita; segunda transação grava desafio `CONSUMED` e `LOGIN_REJECTED`; `409 WALLET_ALREADY_LINKED`; convite continua `PENDING` | NFR4.3 |
| Revogação e verificação juntas | As duas bloqueiam a mesma linha | Quem bloqueia primeiro vence; a outra lê o estado gravado | NFR4.4 |
| Repetição da emissão ou da revogação | Mecanismo comum de idempotência da U1, gravado na mesma transação da operação | Mesmo resultado, sem segundo convite nem segundo evento; sem chave, `400 VALIDATION_FAILED` | NFR4.5 |

A violação de unicidade é reconhecida pelo nome da restrição (`uq_wallet_address`, `uq_invite_used_by`), nunca pela mensagem do banco. Uma violação de outra restrição é falha técnica (5xx).

Testes de concorrência usam uma barreira de início comum entre duas threads com conexões próprias e repetem 50 vezes cada caso (NFR4.2 a NFR4.4).

## Falhas

| Falha | Comportamento | Requisito |
|---|---|---|
| Banco indisponível ou tempo esgotado na verificação | Transação desfeita, 5xx com `correlationId`; nenhuma repetição automática (operação não idempotente); a tela mostra erro técnico e pede um novo desafio | NFR10.2 |
| Arquivo dos termos ausente ou inválido | A API não sobe: o carregador dos termos falha na inicialização com mensagem clara (sem versão vigente, mais de uma vigente, texto vazio) | NFR10.3 |
| Migração repetida | A semeadura do tenant `Beta` usa identificador fixo e inserção condicionada à ausência; aplicar duas vezes não muda nada | NFR10.4 |
| Reinício da API | Dados ficam no banco; os contadores do limite recomeçam do zero (aceito no beta) | NFR10.5 |
| Validade no limite | Relógio injetável (`Clock` da U1) em emissão, consulta e verificação; comparação inclusiva em `expiresAt` | NFR10.6 |

## Disponibilidade e saúde

- Sem meta numérica de disponibilidade no beta local (NFR10.1); a meta vem com a hospedagem.
- A saúde da identidade depende só do banco: a API não sobe sem os termos, então não existe estado "no ar sem termos" para a saúde mostrar.
