# Requisitos de segurança — U1 `u1-mode-skeleton`

Herdam o NFR3 (isolamento e segurança) e o NFR4 (separação dos modos) do intent, com as respostas da Q3 [Q3].

## Autenticação e autorização

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.1 | O tenant e a pessoa vêm sempre do contexto de autenticação, nunca do corpo, da consulta ou de cabeçalho próprio (BR8.1). | Em C1 a C4, um teste de integração envia um `tenantId` ou `accountId` no corpo ou na consulta e confere que o valor é ignorado ou recusado com 400; 0 consultas de dados de cliente sem filtro de tenant (regra ArchUnit do módulo continua verde). |
| NFR3.2 | Um bot de outra pessoa responde 404 `NOT_FOUND` e nunca devolve dado (BR8.2). | Para cada rota da U1 que recebe ou devolve um id de bot, o teste com o id de outra conta recebe 404 e o corpo não contém nenhum campo do bot alheio; a linha do bot alheio nunca aparece em C4. |
| NFR3.3 | As rotas novas (C2, C3, C4) e a criação (C1) exigem login e seguem o novo aceite dos termos como as demais (BR8.3). | Sem token: 401 nas quatro rotas. Com termos desatualizados: 403 `TERMS_REACCEPTANCE_REQUIRED` nas quatro rotas. Nenhuma delas entra na exceção do novo aceite (leitura da carteira do bot e saque). |
| NFR3.4 | A elegibilidade só libera a criação de um bot REAL e nunca autoriza operar (BR2.6); a barreira REAL continua conferindo cada ordem. | 0 caminhos de ordem que leiam o resultado de C2; teste de unidade confere que `RealCreationEligibility` não é dependência de nenhuma classe de `realexecution` ou `riskcontrol.real` (ArchUnit). |

## Auditoria

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.5 | Toda recusa REAL na criação (409 `REAL_NOT_ELIGIBLE`, cinco motivos) é auditada como erro de domínio, e a gravação da auditoria acontece fora da transação do comando, que é desfeita (BR2.4). | Para cada um dos 5 motivos, um teste de integração confere 1 registro de auditoria com o motivo e o `correlationId` e 0 bots criados. |

## Proteção de dados

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.6 | Nenhum endereço de carteira aparece no P&L (C4), nos registros de log da U1 nem nas etiquetas das métricas novas (BR5.8). | Teste de contrato: o esquema de C4 não tem campo de endereço; teste de integração: o corpo de C4 de uma pessoa com carteira do bot ativa não contém o endereço semeado; as etiquetas das métricas do NFR4.6 e do NFR3.10 têm só valores de enum. |
| NFR3.7 | Nenhum segredo, token de sessão, cabeçalho `Authorization`, nonce ou assinatura vai para o log, e nenhum segredo vai para variável `NEXT_PUBLIC_*` ou para código entregue ao navegador. | Gitleaks verde no pré-commit e no workflow `security`; 0 variáveis `NEXT_PUBLIC_*` novas na U1; o teste de captura de log da recusa REAL não encontra o token do pedido. |

## Configuração e caminhos de risco

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.8 | A liberação REAL usada pelo spec de ponta a ponta vem de uma semente que só existe no perfil `test`, ligada por `memetrade.real.seed-test-release`; a quarentena de 24 horas de produção não muda (BR10.2). | Um teste de guarda falha se a configuração de produção (`application.yml` e perfis não `test`) puder ativar o perfil `test` ou a chave da semente; 0 caminhos de produção que encurtem ou ignorem a quarentena de 24 horas. |
| NFR3.9 | Nenhum caminho da U1 assina ou envia transação; o REAL continua desligado por padrão. | Com a configuração padrão (API, worker e TxSigner), o teste obrigatório "REAL desligado por padrão" continua verde: 0 carteiras do bot criadas, 0 assinaturas pedidas ao KMS, 0 transações enviadas; a U1 não acrescenta nenhuma chamada ao TxSigner. |

A contagem das recusas REAL por motivo, sem dado pessoal, é o NFR3.10, definido em `observability-requirements.md`.

## Separação dos modos

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR4.1 | Nenhuma resposta, tela ou teste soma PAPER e REAL num total geral (BR5.1). | Teste de contrato obrigatório: o esquema de C4 não tem campo de total fora dos blocos; teste de integração: a resposta de C4 com valores nos dois modos não contém nenhum número igual à soma dos totais dos dois blocos fora deles; teste de componente do `apps/web` confere que a tela de P&L não mostra total geral. |
| NFR4.2 | Cada bloco de C4 traz só o próprio modo e a própria moeda: PAPER em US$ (decimal exato), REAL em lamports inteiros em texto (BR5.2, BR5.8). | Teste de integração com ordens nos dois modos: o bloco PAPER não contém nenhuma ordem REAL e o bloco REAL não contém nenhuma ordem PAPER; 0 campos de moeda trocada. |
| NFR4.3 | O modo do bot é imutável depois da criação, e `mode == market` em toda resposta (BR1.2, BR1.3). | Edição com `market` recusada com 400; teste de contrato do backend confere `mode == market` em `BotSummary` e `BotDetail`; a restrição `bot_market_immutable` do banco continua. |

## Sources

- `nfr-requirements-questions.md`: Q3 e resumo confirmado [Q3].
- `inception/requirements-analysis/requirements.md`: NFR3, NFR4.
- `construction/u1-mode-skeleton/functional-design/rules.md`: BR1.2, BR1.3, BR2.4, BR2.6, BR5.1, BR5.2, BR5.8, BR8.1 a BR8.3, BR10.2.
- `inception/contract-design/contract-summary.md`: regras comuns (tenant do token, 404), C1 a C4.
- `aidlc/spaces/default/memory/project.md`: Forbidden (log de segredos, `NEXT_PUBLIC_*`, soma dos modos) e Mandated (tenant, separação, REAL desligado).
- `aidlc/spaces/default/memory/team.md`: Testing Posture (testes obrigatórios por risco), Code Style (erros RFC 7807, tenant no código, ArchUnit), Way of Working (Gitleaks).
- Código lido: `RealTestSeeder` e a chave `memetrade.real.seed-test-release`; migração `0019-real-execution.sql` (`bot_market_immutable`).

## Assumptions & Open Questions

1. [assumption] O teste de NFR3.1 aceita as duas respostas do código de hoje para campo não declarado (ignorado ou 400 `VALIDATION_FAILED`, conforme o `BotRoutesIT`); o que importa é que o tenant nunca venha do corpo.
2. [assumption] A U1 não muda o modelo de ameaças do ADR-005: a única superfície nova é de leitura (C2, C3, C4) e a criação REAL, que não movimenta fundos.
