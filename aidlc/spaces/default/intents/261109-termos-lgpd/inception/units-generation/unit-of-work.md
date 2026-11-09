# Units de trabalho — Termos de uso e LGPD

Conversa: português (Brasil). Fontes:
- `inception/domain-design/components.md` e `decisions.md`;
- `inception/requirements-analysis/requirements.md`;
- `inception/user-stories/stories.md`;
- as respostas de `units-generation-questions.md` (Q1 a Q5).

Todas as Units são de ponta a ponta (API, banco, telas e testes) e vão para o mesmo monólito modular e os dois apps atuais. As migrações seguem expand-contract.

## Units

| Unit ID | Directory | Nome | Kind | Complexidade | Implantação |
|---|---|---|---|---|---|
| U1 | `u1-legal-documents` | Documentos legais (fatia fina) | service | M | compartilhada (monólito e apps) |
| U2 | `u2-retention` | Retenção e auditoria minimizada | service | M | compartilhada |
| U3 | `u3-reacceptance` | Novo aceite | service | M | compartilhada |
| U4 | `u4-data-requests` | Pedidos de titular | service | L | compartilhada |
| U5 | `u5-anonymization` | Anonimização | service | L | compartilhada |

## U1 — `u1-legal-documents`

- **Descrição:** catálogo de termos e política v2 no `Identity` e páginas públicas nos dois apps. É a fatia fina que roda de ponta a ponta: `/privacidade` servida pela API, a partir do catálogo com a versão vigente, e mostrada sem login.
- **Fronteiras:**
  - `Identity`: `TermsVersion`, ativação com revisão jurídica e campos de implantação vindos da configuração;
  - `GuestWebApp` e `AdminWebApp`: `LegalDocument`, `SiteFooter`, `/termos`, `/privacidade`, histórico de versões e botão "Fazer um pedido".
- **Responsabilidades:** US1.1, US1.2, US1.3, US2.1, US2.2, US2.4, US2.5, US2.6.
- **Notas:**
  - textos v2 redigidos a partir do sistema e revisados pela assessoria antes da publicação;
  - a tabela de retenção da política lê o catálogo de retenção quando U2 existir; até lá, a seção usa o texto declarado no catálogo de termos;
  - o botão "Fazer um pedido" leva à tela Conta, que já existe; a seção "Meus dados (LGPD)" chega em U4. O spec da fatia fina confere só o destino do botão;
  - o spec novo de ponta a ponta entra no `verify.sh` pelo responsável do projeto.

## U2 — `u2-retention`

- **Descrição:** minimização da carteira na auditoria, expurgos novos e catálogo de retenção com teste de conformidade.
- **Fronteiras:**
  - `Audit`: HMAC e remoção do endereço completo de `details`, inclusive no login do administrador;
  - `Identity`: expurgos de desafios (24 h após expirar) e de sessões e digests (30 dias);
  - `Privacy`: cria a base do módulo novo (pacote, interface pública, `RetentionRule`) e o teste que compara a tabela da política com os expurgos.
- **Responsabilidades:** US1.4, US3.1, US3.2.
- **Notas:** chave do HMAC como segredo de configuração; indicadores de saúde dos expurgos novos; checklist de abertura com banco novo e consulta de conferência em `audit_event` e `audit_outbox`.

## U3 — `u3-reacceptance`

- **Descrição:** trava de novo aceite e o aceite em si.
- **Fronteiras:**
  - `Identity`: filtro web, lista de rotas liberadas por prefixos configuráveis (U4 acrescenta o prefixo dos pedidos) e rota de aceite com `TERMS_VERSION_OUTDATED`;
  - `GuestWebApp`: T7 e o convite com as duas caixas.
- **Responsabilidades:** US2.3, US4.1, US4.2.
- **Notas:** o worker não passa pelo filtro; teste obrigatório do time "versão nova exige novo aceite".

## U4 — `u4-data-requests`

- **Descrição:** estende o módulo `privacy` (criado em U2) com o ciclo de vida dos pedidos de titular. Registra o prefixo das rotas de pedidos na lista liberada do filtro de U3, com o teste correspondente.
- **Fronteiras:**
  - `Privacy`: `DataSubjectRequest`, `IdentityVerification`, resumo de Acesso, vencimento e expurgo de 12 meses;
  - interfaces de leitura novas em `Bots`, `Ledger` e `Feedback` (contagens) e em `Identity` (resumo e conferência da carteira);
  - `GuestWebApp`: T5;
  - `AdminWebApp`: T6, T6a, T6b, T6c.
- **Responsabilidades:** US5.1 a US5.9.
- **Notas:**
  - a mensagem de verificação nunca serve para login nem para operar;
  - nada de texto de pedido, mensagem ou assinatura em log ou evento;
  - isolamento por tenant e pessoa.

## U5 — `u5-anonymization`

- **Descrição:** anonimização coordenada por `Privacy` numa transação única, e o bloqueio da conta anonimizada.
- **Fronteiras:**
  - `Privacy`: coordenação;
  - interfaces novas de escrita em `Bots` (parar e renomear), `Feedback` (apagar) e `Identity` (revogar sessões, apagar desafios e carteira, estado `ANONYMIZED`, com migração do CHECK e da carteira sem endereço);
  - `Audit`: `ACCOUNT_ANONYMIZED`;
  - `AdminWebApp`: T8.
- **Responsabilidades:** US6.1, US6.2, US6.3.
- **Notas:** teste obrigatório "a anonimização não deixa a carteira em nenhuma tabela nem log"; testes de falha no meio, repetição e concorrência; o ledger não é tocado.
