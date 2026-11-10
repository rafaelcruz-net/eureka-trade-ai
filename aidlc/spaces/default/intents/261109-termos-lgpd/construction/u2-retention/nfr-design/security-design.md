# Desenho de segurança — U2 `u2-retention`

Conversa: português (Brasil). Implementa `construction/u2-retention/nfr-requirements/security-requirements.md` (NFR1.1 a NFR1.4, NFR2.1, NFR6.1 a NFR6.4) e as regras BR2.1 a BR2.4, BR2.15 e BR2.19. Respostas em `nfr-design-questions.md`.

## Minimização da carteira na auditoria

| Requisito | Desenho |
|---|---|
| NFR1.1 | O `AuditEventCommand` ganha o campo `wallet`, só para endereço válido, e esse campo nunca é serializado. O `JdbcAuditEventPublisher` chama o `WalletHasher` do Audit, que grava `actor` com a máscara existente (`WalletAddress.mask`) e põe `walletHmac` no payload do `audit_outbox`. O relay copia o `walletHmac` para a coluna `audit_event.wallet_hmac` (BR2.18):<ul><li>o `AuditPayloadCodec` passa a codificar o `walletHmac` como campo de primeiro nível do payload;</li><li>o `decode` passa a devolver um registro do Audit (`DecodedAuditEvent`, com o comando e um `walletHmac` opcional);</li><li>o `insertIntoTrail` grava a coluna, que fica nula quando o payload não tem o campo.</li></ul>O construtor canônico de `AuditEventCommand` ganha o campo `wallet`. As fábricas existentes (`anonymous`, `person`, `admin`, `system`) mantêm as assinaturas e passam `null`, e uma fábrica nova recebe a carteira. Os chamadores passam a usar o campo `wallet`: `LoginVerificationService`, `AdminLoginService`, `InviteAccountCreation` e os eventos de login aceito |
| NFR1.2 | O `AuditEventValidator` ganha uma checagem em todo valor de `details`, inclusive dentro de listas e mapas:<ul><li>procura sequências de 32 a 44 caracteres do alfabeto Base58, delimitadas por qualquer caractere fora do alfabeto ou pelo início e fim do texto;</li><li>só recusa a sequência que decodifica para exatamente 32 bytes, o tamanho de uma chave pública Ed25519. É a mesma confirmação por decodificação da checagem de assinatura (`BASE58_RUN` e `decodedLength`).</li></ul>Uma recusa gera `AuditPublishRejectedException` com o campo e o motivo `FULL_WALLET`, nunca o valor. Os testes cobrem valores longos legítimos que não podem ser recusados: UUID sem hífens, hash hexadecimal, código de convite e digest. A checagem de assinatura (64 bytes) continua |
| NFR1.3 | Nos dois serviços de login, um endereço que falha em `WalletAddress.isValid` gera `actor=anonymous` e `details.addressFormat=INVALID`, sem o campo `wallet`. O `WalletHasher` nunca recebe texto que não passou na validação |
| NFR1.4 | Os jobs e os indicadores registram só `purge`, contagem, lotes, duração e a classe da exceção. O `LogScanIT` existente continua procurando carteira completa nos logs |
| NFR2.1 | O evento de contagem é `AuditEventCommand.system(...)`, sem tenant, com `details` só com as contagens |

## Chave do HMAC

| Requisito | Desenho |
|---|---|
| NFR6.1 | Propriedade `memetrade.audit.wallet-hmac-key`, ligada a `MEMETRADE_AUDIT_WALLET_HMAC_KEY` e validada na subida por `@Validated` com no mínimo 32 bytes em UTF-8. A chave fica numa `SecretKeySpec` dentro do `WalletHasher` e nunca é exposta por getter, `toString` nem `actuator/env` (a chave entra na lista de sanitização do actuator). O worker valida a mesma propriedade |
| NFR6.2 | O `.env.example` recebe o nome sem valor, e o `docker-compose.yml` repassa a variável para `api` e `worker` como `${MEMETRADE_AUDIT_WALLET_HMAC_KEY:?}` (obrigatória). O responsável do projeto acrescenta a geração da chave ao `verify.sh` e o valor de exemplo ao passo `Compose config` do `ci.yml`. Os testes injetam uma chave fixa de teste pela propriedade |
| NFR6.3 | O runbook de incidentes ganha o passo "troca da chave HMAC da auditoria", com o efeito na correlação. Não há rotação programada |
| NFR6.4 | Os jobs novos usam o `JdbcClient` principal do Identity. A regra ArchUnit existente (nenhum módulo lê tabela de outro) cobre as três tabelas |

## Ameaças

O endereço completo vazando, o texto do atacante virando HMAC e a chave exposta são controlados por NFR1.1 a NFR1.3 e NFR6.1. Um chamador novo que ponha a carteira em `details` é recusado pelo validador (NFR1.2).
