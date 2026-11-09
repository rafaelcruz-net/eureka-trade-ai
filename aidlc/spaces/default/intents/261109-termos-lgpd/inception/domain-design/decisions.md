# Decisões de arquitetura — Termos de uso e LGPD

Conversa: português (Brasil). Fontes:
- `inception/requirements-analysis/requirements.md`;
- `inception/user-stories/stories.md`;
- `aidlc/spaces/default/codekb/eureka-trader-ai/architecture.md` e `component-inventory.md`;
- `inception/practices-discovery/team-practices.md`;
- `domain-design-questions.md` (Q1 a Q5).

## ADR-001: Módulo novo `privacy` para os direitos do titular

- **Context:** pedidos de titular, verificação, resumo de Acesso e anonimização tocam dados de vários módulos e seguem prazos legais. O time proíbe um módulo de ler tabelas de outro.
- **Decision:** criar o módulo `privacy`, dono de `DataSubjectRequest`, `IdentityVerification` e `RetentionRule`. Ele coordena os outros só pelas interfaces públicas (Q1).
- **Consequences:**
  - A favor: a regra de negócio legal fica isolada e testável, e o `identity` não passa a depender de `bots` nem de `ledger`.
  - Contra: um módulo a mais, e interfaces novas em `bots`, `feedback` e `ledger`.
- **Alternatives Rejected:** tudo em `identity`, porque misturaria autenticação com direitos do titular e criaria dependências de `identity` para `bots` e `ledger`.

## ADR-002: Anonimização numa transação única, coordenada por `privacy`

- **Context:** a exclusão precisa apagar ou trocar dados em `bots`, `feedback` e `identity` sem tocar no ledger. Uma falha no meio não pode deixar a conta meio anonimizada (US6.2).
- **Decision:** `privacy` abre uma transação de banco e chama, em ordem:
  1. `bots`: parar e renomear;
  2. `feedback`: apagar;
  3. `identity`: revogar sessões, apagar os desafios de login daquele endereço, apagar a carteira e marcar `ANONYMIZED` (com a migração expand-contract do CHECK de status e da carteira sem endereço);
  4. `audit`: publicar `ACCOUNT_ANONYMIZED` pela tabela de saída, na mesma transação.

  Qualquer falha desfaz tudo. Travar os bots primeiro impede que um ciclo novo crie ordem durante a anonimização. Repetir numa conta `ANONYMIZED` não muda nada (Q2).
- **Consequences:**
  - A favor: tudo ou nada, sem estado parcial, e auditoria coerente.
  - Contra: a transação toca tabelas de três módulos (aceitável num monólito com um único banco), e a ordem de travas precisa de teste de concorrência.
- **Alternatives Rejected:** eventos assíncronos por módulo, porque uma falha deixaria o estado parcial e exigiria compensação.

## ADR-003: Resumo de Acesso por interfaces públicas de leitura

- **Context:** o resumo junta dados de `identity`, `bots`, `ledger`, `feedback` e dos próprios pedidos (US5.4).
- **Decision:** `privacy` chama interfaces públicas de leitura de cada módulo; nenhuma leitura direta de tabela alheia (Q3).
- **Consequences:**
  - A favor: respeita as fronteiras, e cada módulo controla o que expõe (carteira só mascarada).
  - Contra: várias chamadas, aceitável pelo volume baixo de pedidos.
- **Alternatives Rejected:** consulta SQL única no `privacy`, porque quebraria a regra de fronteira do time.

## ADR-004: Trava de novo aceite num filtro web do `identity`

- **Context:** com versão nova, o convidado precisa aceitar antes de usar as demais rotas, mas os bots PAPER ativos continuam (US4.1).
- **Decision:** um filtro web do `identity`, depois da autenticação, compara a versão aceita com a vigente e responde `403 TERMS_REACCEPTANCE_REQUIRED` fora da lista de rotas liberadas. O worker não passa pelo filtro (Q4).
- **Consequences:**
  - A favor: um ponto só de controle, e os módulos não precisam saber dos termos.
  - Contra: a lista de rotas liberadas precisa de teste para não bloquear Conta, pedidos ou sair. As rotas de pedidos do `privacy` entram por um prefixo configurado, um acoplamento declarado e coberto pelo teste da lista.
- **Alternatives Rejected:** checagem em cada módulo, que espalharia a regra e facilitaria esquecer uma rota.

## ADR-005: Minimização da carteira na trilha de auditoria com HMAC

- **Context:** a trilha guarda o endereço completo por 12 meses e não aceita edição. A anonimização precisa que nenhum resíduo fique (US3.2, US6.2), e ainda não há convidado real.
- **Decision:** o `audit` grava só o endereço mascarado e um HMAC-SHA-256 com chave secreta da configuração, antes da abertura do beta. Isso vale também para `details` nos logins do convidado e do administrador. A trilha continua sem edição.

  **Descarte dos dados de teste:** o ambiente do beta nasce de um banco novo, criado pelas migrações, sem copiar nenhum dado do ambiente local. Assim, nenhum evento recente com a carteira completa precisa ser apagado da trilha, que recusa esse DELETE. Quem executa é o responsável do projeto, na implantação. O checklist de abertura confere, com uma consulta, que nem `audit_event` nem `audit_outbox` têm endereço completo.
- **Consequences:**
  - A favor: correlação sem guardar a carteira, e nada a reescrever na trilha.
  - Contra: uma chave nova para gerenciar (segredo; NFR6), e consultas de auditoria por carteira passam a calcular o HMAC.
- **Alternatives Rejected:** permitir reescrever eventos antigos, porque enfraqueceria a garantia append-only da trilha.

## ADR-006: Telas por feature em cada app, sem pacote compartilhado

- **Context:** os dois apps precisam do documento legal e do rodapé; o administrador tem telas próprias de pedidos (Q5).
- **Decision:** `features/legal`, `features/account` e `features/terms-reacceptance` no app do convidado; `features/data-requests` no app do administrador. O `LegalDocument` e o `SiteFooter` ficam em cada app, com tipos gerados do contrato.
- **Consequences:**
  - A favor: segue a estrutura atual e evita um pacote novo no monorepo.
  - Contra: dois componentes pequenos duplicados entre os apps.
- **Alternatives Rejected:** um pacote de UI compartilhado, que traria build e versionamento novos para dois componentes pequenos.
