# Requisitos de segurança — U2 Trilha de auditoria

Requisitos de segurança e privacidade da Unit `u2-audit-trail`, derivados das regras de [rules.md](../functional-design/rules.md) (BR1 a BR4), dos fluxos F1 a F6 de [functional-spec.md](../functional-design/functional-spec.md), do FR2.3, FR3.3, NFR5, NFR6 e NFR7 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos contratos C8, C9 e C10 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das regras do `project.md`. As decisões vêm de [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q5, Q6, Q7).

## Classificação dos dados da U2

| Dado | Classe | Onde aparece | Proteção |
|---|---|---|---|
| Evento da trilha (`audit_event`) | Confidencial; contém dado pessoal (endereço de carteira completo em `details`, A5) | Banco e resposta da consulta do administrador | Append-only; leitura só pela rota administrativa; exclusão só pelo usuário de retenção, antes do corte de 12 meses |
| Entrada da tabela de saída (`audit_outbox.payload`) | Confidencial; mesma forma do evento | Banco | Escrita só pelo publicador; leitura só pelo relay; entregue sai em 7 dias |
| `actor` antes do login | Confidencial | Evento | Sempre mascarado (4 + 4 caracteres) |
| Senha do usuário de banco de retenção | Restrito | `.env` local | Nunca no repositório, em log ou em métrica |
| Senha do usuário de migração (dono do esquema) | Restrito | `.env` local | Idem |
| Token de administrador | Restrito | Cabeçalho `Authorization` | Nunca em log nem no evento (herdado da U1) |
| Tabela de bloqueio das tarefas agendadas | Interno | Banco | Escrita só pelo usuário da aplicação; sem dado pessoal |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR6.1 | Só o administrador lê a trilha | `GET /api/v1/admin/audit-events` sem token responde `401 UNAUTHENTICATED`; com token de convidado responde `403 FORBIDDEN_ROLE` e grava `REQUEST_FORBIDDEN` com a rota e o papel; com token de administrador responde `200`. Antes da U8, o contexto de administrador existe só nos testes, e um teste confere que nenhum código de produção o cria | BR2.1, NFR6, C8 |
| NFR6.2 | Usuário de banco próprio para a retenção | Login de banco separado, com senha própria no `.env`, só com leitura e exclusão em `audit_event` e nenhum outro privilégio; pool de 1 conexão usado só pelo expurgo; o usuário da aplicação não é membro desse papel e não consegue assumi-lo. Testes: com o usuário de retenção, `INSERT` e `UPDATE` em `audit_event` falham e qualquer acesso a outra tabela falha; com o usuário da aplicação, assumir o papel de retenção falha | BR3.2, Q6 |
| NFR6.3 | Usuário da aplicação sem poder de alterar a estrutura do banco | As migrações rodam com um usuário de migração dono do esquema; o usuário da aplicação tem só leitura e escrita de dados nas tabelas que usa, sem criar, alterar, remover ou esvaziar tabelas e sem desligar gatilhos. Testes: `ALTER TABLE audit_event DISABLE TRIGGER`, `DROP` e `TRUNCATE` com o usuário da aplicação falham | Q7, BR1.3 |
| NFR6.4 | Proteção da trilha no próprio banco | O gatilho de `audit_event` recusa todo `UPDATE`; recusa `DELETE` que não venha do usuário de retenção; recusa `DELETE` do usuário de retenção quando `occurred_at` não é anterior ao início do dia UTC menos 12 meses. O limite de 12 meses no banco é fixo na migração, e a configuração da aplicação não aceita valor menor (`retentionMonths` mínimo 12). Testes com eventos de 13 e de 11 meses | BR1.3, BR3.1 |
| NFR6.5 | Credenciais de banco fora do repositório | Senhas dos usuários de aplicação, de retenção e de migração só no `.env` local ignorado pelo git; `.env.example` com as chaves sem valores; Gitleaks no pré-commit e na CI; nenhuma senha em log, métrica ou mensagem de erro | NFR6, `team.md` |
| NFR6.6 | Entrada da consulta validada | `from` e `to` obrigatórios em UTC, `to` maior que `from`, período de até 31 dias; `limit` de 1 a 100; `action` com até 64 caracteres em maiúsculas com sublinhado; `cursor` com forma válida; parâmetro não declarado recusado. Toda violação responde `400 VALIDATION_FAILED` com a lista de campos, sem SQL nem stack trace; consultas sempre parametrizadas | BR2.2, NFR6 |
| NFR6.7 | Resposta da consulta sem cópia em cache | A resposta da consulta traz `Cache-Control: no-store`, porque contém dado pessoal completo | BR2.4, NFR7 |
| NFR6.8 | Portões de segurança cobrindo a dependência nova | CodeQL e OWASP Dependency-Check rodam sobre o código da U2 e a biblioteca de bloqueio de tarefas agendadas (Q5), com versão fixada no `pom.xml`; achado Crítico ou Alto bloqueia o merge | NFR6, `team.md` |
| NFR6.9 | Risco aceito e registrado: administrador do próprio banco | Sem prova criptográfica de adulteração no beta (Q7). O risco de um administrador do banco alterar eventos sem rastro fica registrado como aceito para o beta local; a cadeia de resumos SHA-256 é candidata obrigatória de avaliação no G1, antes de qualquer ambiente compartilhado | Q7, NFR7 |
| NFR5.1 | Nenhum acesso de convidado à trilha | Nenhuma rota do convidado lê `audit_event` ou `audit_outbox`; o `tenantId` do evento vem do `Principal` do chamador (C10), nunca da requisição. Teste: a ação de um convidado do tenant A gera evento com o tenant A mesmo quando a requisição traz outro `tenantId` | NFR5, FR2.1, C10 |
| NFR7.1 | Endereço completo só na trilha | Fora de `details` e da resposta da consulta, o endereço aparece com 4 primeiros e 4 últimos caracteres: em `actor`, logs, métricas e qualquer outra resposta | BR4.1, BR2.4, NFR7 |
| NFR7.2 | Nenhum segredo no evento | O publicador recusa evento cujo `details` ou `targetRef` tenha valor em forma de JWT, de valor `Bearer` ou de assinatura Base58 de 64 bytes, ou chave `nonce`, `signature`, `token`, `accessToken`, `refreshToken`, `authorization` ou `password` (sem diferença de maiúsculas); a transação da ação desfaz. Um teste por chave e por forma proibida | BR1.4, NFR7 |
| NFR7.3 | Varredura automática da suíte | Ao fim da suíte, a varredura procura em logs e métricas o endereço completo usado nos testes; em logs, métricas, `payload` da tabela de saída e `details` da trilha, os tokens de acesso e de renovação emitidos, qualquer forma de JWT, o cabeçalho `Authorization`, o prefixo `Bearer` e os nonces e assinaturas emitidos. Qualquer achado falha o build, informando local e tipo, nunca o valor. O endereço completo em `details` da trilha não conta como achado | BR4.2, NFR7 |
| NFR7.4 | Retenção mínima aplicada | Eventos da trilha guardados por 12 meses contados de `occurredAt`; entradas entregues da tabela de saída apagadas em 7 dias, o que limita a segunda cópia do endereço completo; entradas pendentes nunca apagadas | BR3.1, BR3.4, NFR7 |
| NFR7.5 | Consulta auditada sem copiar resultados | Cada consulta respondida com `200` grava `AUDIT_QUERIED` com o administrador como ator e `from`, `to` e `action` em `details`; o evento nunca contém itens da resposta | BR2.5, FR3.3 |
| NFR7.6 | Proteção em trânsito, em repouso e em backups antes do G1 | No ambiente local: HTTP em `localhost` e sem criptografia de disco, como na U1. Antes de qualquer ambiente compartilhado (G1): TLS 1.2 ou superior, criptografia em repouso do banco e dos backups, e retenção dos backups alinhada aos 12 meses da trilha (ou o prazo maior documentado e aprovado) | NFR7, U1 (NFR7.2) |

## Modelo de ameaças (STRIDE) dos fluxos da U2

| Ameaça | Exemplo | Mitigação | Requisito |
|---|---|---|---|
| Falsificação | Convidado chama a rota de consulta com o próprio token | Token de convidado só vale em rotas do convidado; `403` auditado | NFR6.1 |
| Falsificação | Contexto de administrador criado por código de produção antes da U8 | Contexto só nos testes, com teste que confere a ausência em produção | NFR6.1 |
| Adulteração | Aplicação comprometida altera ou apaga eventos | Gatilho recusa `UPDATE`; `DELETE` só do usuário de retenção e antes do corte; aplicação sem poder de desligar o gatilho | NFR6.3, NFR6.4 |
| Adulteração | Expurgo apaga eventos recentes por erro de configuração | Corte de 12 meses fixo no banco e mínimo de 12 na configuração | NFR6.4 |
| Adulteração | Administrador do banco muda eventos | Risco aceito no beta local; cadeia de resumos avaliada no G1 | NFR6.9 |
| Repúdio | Administrador nega ter consultado a trilha | `AUDIT_QUERIED` gravado em cada consulta | NFR7.5 |
| Repúdio | Expurgo apaga sem deixar rastro | `AUDIT_RETENTION_PURGED` com corte, quantidade e resultado (BR3.3) | NFR7.4 |
| Divulgação | Endereço completo, token ou nonce em log, métrica ou evento | Mascaramento, recusa no publicador e varredura que falha o build | NFR7.1, NFR7.2, NFR7.3 |
| Divulgação | Resposta da consulta guardada em cache | `Cache-Control: no-store` | NFR6.7 |
| Negação de serviço | Consulta de período longo ou página grande | Período de até 31 dias, `limit` até 100, índices da trilha | NFR6.6, NFR2.1 |
| Negação de serviço | Expurgo longo trava a trilha | Lotes de até 1000, limite de 30 minutos, usuário e pool próprios | NFR3.3, NFR6.2 |
| Elevação de privilégio | Código da aplicação assume o papel de retenção | Login separado; aplicação não é membro do papel | NFR6.2 |
| Elevação de privilégio | Leitura de eventos de outro tenant por convidado | Nenhuma rota do convidado lê a trilha | NFR5.1 |

## Conformidade

- **LGPD:** o endereço de carteira é tratado como dado pessoal até parecer em contrário (A5). A trilha guarda o endereço completo por 12 meses (Q8 do design funcional), com acesso só do administrador e consulta auditada. O prazo continua sujeito ao inventário de dados e à revisão jurídica (FR14.3, C-07, C-08); uma mudança de prazo muda `retentionMonths` e a migração do corte.
- **Auditoria:** cada ação sensível tem exatamente um evento (FR2.3); a própria trilha registra quem a consulta e cada expurgo (FR3.3, BR3.3).
- **REAL desligado:** a U2 não tem caminho de assinatura nem de envio de transação (FR15.2).
