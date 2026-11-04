# Requisitos de segurança — U3 Convites, contas e sessões

Requisitos de segurança da Unit `u3-identity-access`, derivados das regras de [rules.md](../functional-design/rules.md), dos fluxos de [functional-spec.md](../functional-design/functional-spec.md), do NFR5, NFR6 e NFR7 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos contratos C1, C3, C8, C9 e C17 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das regras do `project.md` e do `team.md`. As decisões vêm de [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q2, Q3 e Q4).

## Classificação dos dados da U3

| Dado | Classe | Onde aparece | Proteção |
|---|---|---|---|
| Código do convite | Restrito (credencial de uso único) | Só na resposta `201` da emissão e no link entregue ao convidado | Nunca guardado: o banco tem só o resumo SHA-256 e os 4 últimos caracteres; fora de log, trilha, métrica e registro de idempotência |
| Endereço da carteira | Pessoal (LGPD, A5) | Banco (`Wallet.address`) | Completo só no banco; mascarado (4 + `…` + 4) em log, métrica, trilha, tela Conta e `Invite.issuedBy` |
| Aceite dos termos (versão, horário, convite) | Pessoal | Banco, tela Conta | Leitura só pela própria pessoa (tenant e pessoa do token) |
| Convite (tenant, validade, estado, datas) | Interno | Banco, rotas administrativas | Leitura e escrita só pelo administrador |
| Termos e aviso de risco | Público | Arquivo do repositório, consulta do convite | Sem dado de cliente |
| Contadores do limite de consultas | Interno | Memória do processo da API | Chave pelo IP; não aparece em log |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR6.1 | Código do convite imprevisível | O código tem 256 bits de um gerador criptográfico do sistema, codificados em base64url sem preenchimento (43 caracteres). Teste: 10 000 códigos gerados têm 43 caracteres do alfabeto base64url e nenhum se repete; o gerador vem do relógio e do aleatório injetáveis só nos testes | BR1.1, Q2 do design funcional |
| NFR6.2 | Só o resumo do código é guardado | O banco guarda `codeDigest` (SHA-256 do código, sem chave secreta, Q2) com índice único, e `codeSuffix`. A busca é pelo resumo. Teste: depois de emitir, nenhuma coluna de nenhuma tabela contém o código (varredura do esquema da U3 no teste de integração) | BR2.3, Q2 |
| NFR6.3 | Repetição da emissão não expõe o código | O registro de idempotência da emissão guarda a resposta sem o campo `code`, gravado **na mesma transação** do convite e do `INVITE_ISSUED`, para que uma queda entre os dois não gere um segundo convite com a mesma chave. Teste: repetir a emissão com a mesma chave devolve o mesmo `inviteId` sem `code`; o registro de idempotência não contém o código | BR1.8, revisão do design funcional |
| NFR6.4 | Código fora de log, trilha e métrica | Logs e métricas usam o modelo da rota (`/api/v1/invites/{code}`); a trilha usa só o `inviteId`. O teste de varredura de logs da U1 (`LogScanIT`) passa a emitir, consultar e usar um convite e falha se o código aparecer em qualquer linha de log, evento ou métrica | BR2.3, NFR7 |
| NFR6.5 | Link do convite sem vazamento por Referer | A página do convite no app do convidado responde com `Referrer-Policy: no-referrer`. Teste de ponta a ponta confere o cabeçalho | BR2.3 |
| NFR6.6 | Limite de consultas do convite | `GET /api/v1/invites/{code}` aceita até 10 consultas por minuto por IP, com contador separado do da entrada; acima disso, `429 RATE_LIMITED` com `Retry-After`, decidido antes de procurar o convite, então a resposta não revela se o código existe. Teste: a 10ª passa, a 11ª recebe `429`, depois do `Retry-After` volta a passar | BR2.2, Q5 do design funcional |
| NFR6.7 | Código mal formado igual a desconhecido | Código fora do formato (tamanho diferente de 43 ou caractere fora do base64url) responde `404 INVITE_NOT_FOUND` com a mesma mensagem de código desconhecido, sem consultar o banco. Na verificação, `inviteCode` mal formado tem o mesmo tratamento | BR2.1, BR3.2 |
| NFR6.8 | Verificação com convite só com os campos do contrato | A verificação rejeita campo extra (`additionalProperties: false`), limita `acceptedTermsVersion` a 32 caracteres e ignora qualquer `tenantId` enviado. Nenhum campo de tela ou de API aceita frase secreta, mnemônica ou chave privada. Teste de contrato e teste de arquitetura (nenhuma API de geração ou importação de chave no código de produção) | BR3.9, AC1.2.5, `project.md` |
| NFR6.9 | Assinatura de login sem reutilização | Toda recusa da verificação com convite (convite, papel, carteira, tenant, termos, carteira duplicada na gravação) deixa o desafio `CONSUMED` e grava um único `LOGIN_REJECTED`; numa falha técnica nada é consumido. Teste obrigatório: reenviar a mesma assinatura depois de uma recusa recebe a recusa de desafio da U1; a assinatura de login nunca autoriza operação | BR3.6, `team.md` (testes por risco) |
| NFR6.10 | Rotas de convite só do administrador | `/api/v1/admin/invites` e `/revoke` com token de convidado respondem `403 FORBIDDEN_ROLE` e gravam `REQUEST_FORBIDDEN`; sem token, `401`. Teste de arquitetura: `IdentityAdministration` não é usada por nenhum controlador do convidado. Sem limite de requisições próprio nessas rotas no beta (Q4): o filtro de administrador e a chave de idempotência bastam | BR1.5, AC7.1.4, Q4 |
| NFR6.11 | Retorno à origem sem redirecionamento aberto | O parâmetro `origem` aceita só caminho interno do app do convidado. Teste com `//exemplo.com`, `https://exemplo.com`, `/\exemplo.com`, `javascript:alert(1)`, caminho administrativo e valor codificado em URL: todos levam à tela inicial | BR4.2 |
| NFR6.12 | REAL indisponível na conta | `GET /api/v1/account` responde sempre `realMode: UNAVAILABLE`; a tela Conta não tem botão, chave nem link para REAL. Teste obrigatório de REAL desligado por padrão inclui a tela Conta | BR5.3, `project.md` |
| NFR6.13 | Textos sem promessa de lucro | O catálogo de termos proibidos da U4 roda também sobre os textos da entrada com convite, dos termos e do aviso de risco; falha bloqueia o merge | BR6.2, `project.md` |
| NFR5.1 | Conta só da própria pessoa | `GET /api/v1/account` lê pessoa e tenant do token; não há parâmetro de identificação. A conta nasce no tenant do convite, nunca num tenant vindo do corpo. A suíte negativa multi-tenant inclui a tela Conta e a verificação com `tenantId` forjado no corpo | NFR5, BR5.1, `project.md` |
| NFR5.2 | Única exceção à regra de tenant registrada | Só a emissão de convite recebe `tenantId` do cliente, e só do administrador (ADR-002); o registro de rotas da U1 marca essa rota como exceção nomeada, e o teste do registro falha se outra rota receber tenant do corpo | C8, ADR-002 |
| NFR7.1 | Endereços mascarados | `Invite.issuedBy`, logs, métricas e eventos da U3 mostram o endereço mascarado; o completo fica só em `Wallet.address`. A varredura de logs da U1 cobre as rotas da U3 | NFR7, BR3.5 |
| NFR7.2 | Retenção dos convites | Todos os convites ficam guardados durante o beta (Q3); a regra de retenção de convites, aceites e carteiras sai do inventário de dados (SP-3), aprovado antes dos convites reais. Nenhuma limpeza automática na U3 | Q3, NFR7 |

## Ameaças consideradas (STRIDE)

| Categoria | Ameaça | Controle |
|---|---|---|
| Falsificação | Adivinhar um código de convite | 256 bits aleatórios (NFR6.1); limite por IP na consulta (NFR6.6) |
| Falsificação | Usar a assinatura de login para outra coisa | Nonce de uso único da U1 (NFR6.9); assinatura não autoriza trading |
| Adulteração | Escolher o tenant da própria conta | Tenant vem do convite e do token, nunca do corpo (NFR5.1, NFR5.2) |
| Repúdio | Negar ter aceitado os termos | `TermsAcceptance` imutável com versão, horário e convite; `ACCOUNT_CREATED` na trilha |
| Divulgação | Vazamento do código por log, banco ou Referer | Só o resumo guardado; fora de log e idempotência; `no-referrer` (NFR6.2 a NFR6.5) |
| Divulgação | Descobrir se um código existe | `429` antes da busca; mal formado igual a desconhecido (NFR6.6, NFR6.7) |
| Negação de serviço | Inundar a consulta do convite | Limite por IP (NFR6.6) |
| Elevação de privilégio | Convidado chamar rotas de convite | Filtro de administrador e teste de arquitetura (NFR6.10) |
| Elevação de privilégio | Redirecionamento para site falso depois da entrada | `origem` só interna (NFR6.11) |

## Conformidade

- **LGPD:** endereço de carteira e aceite tratados como dados pessoais (A5); retenção definida pelo inventário de dados (SP-3) antes dos convites reais (NFR7.2).
- **Termos e aviso de risco:** a versão aceita fica registrada; o texto final depende da revisão jurídica prevista no PRD antes do beta com convidados reais.
