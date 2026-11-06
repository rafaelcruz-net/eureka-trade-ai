# Componentes de frontend — U8 Administração e feedback

Há dois apps: o novo app do administrador (`apps/admin`, Next.js, endereço próprio, tipos gerados só da especificação administrativa) e o app do convidado (`apps/web`), que ganha o diálogo de feedback. Os textos de interface são em português do Brasil e os horários aparecem no fuso do navegador.

## App do administrador

| Componente | Responsabilidade | Estado e dados | Regras |
|---|---|---|---|
| `AdminLayout` | Cabeçalho "MemeTrade Admin" com Estado, Convites, Kill switch e Feedback, o endereço mascarado e "Sair" | Sessão na memória; estado do kill switch para a faixa | BR7.1, BR1.6 |
| `KillSwitchBanner` | Faixa "Kill switch ACIONADO", "Pausa pendente em N bots" e "Pausa ainda sendo aplicada" | `state`, `pendingBots`, `reconcileNeeded` | BR7.1, BR2.3 |
| `AdminSignIn` | Conectar carteira, pedir desafio, assinar, verificar | carregando, erro, "Acesso restrito" | BR1.1, BR1.2, BR7.4 |
| `SystemStatusPage` (A1) | Contagens, estado das fontes, recusas de hoje, atraso da trilha | carregando, erro, parcial (`unknown` por parte) | BR3.1–BR3.3 |
| `InvitesPage` (A2) | Lista paginada com estado em texto | cursor, itens | BR5.2 |
| `IssueInviteDialog` | Tenant (lista dos ativos), validade, código mostrado uma vez com "Copiar link" | Idempotency-Key do diálogo; erros por campo | BR5.1 |
| `RevokeInviteDialog` | Confirmação "Revogar este convite?" | 409 com motivo | BR5.3 |
| `KillSwitchPage` | Estado atual, quem, quando e motivo; ações Acionar ou Desligar | `GET` ao abrir | BR2.7 |
| `KillSwitchDialog` | Confirmação com o número de bots e o motivo (1 a 500) | Idempotency-Key do diálogo | BR7.2, BR2.6 |
| `FeedbackListPage` | Lista do mais novo para o mais antigo: tenant, tela, horário, texto puro | cursor | BR6.5, BR6.6 |
| `RestrictedAccess` | "Acesso restrito" e "Sair" | — | BR7.4 |

## App do convidado

| Componente | Responsabilidade | Estado e dados | Regras |
|---|---|---|---|
| `FeedbackButton` | Item "Enviar feedback" no cabeçalho atual | Guarda o foco para devolver | BR7.3 |
| `FeedbackDialog` (S9) | Texto com contador "n / 2.000", tela de origem pela rota, aviso "visível só para a administração" | texto, enviando, erro com motivo; Idempotency-Key do diálogo | BR6.1, BR6.3, BR7.3 |

## Validação no cliente

- O motivo do kill switch e o texto do feedback não podem ficar vazios depois de tirar espaços. O motivo tem até 500 caracteres e o feedback até 2.000 pontos de código. O servidor confere de novo.
- A validade do convite fica entre 1 hora e 30 dias. O servidor confere de novo.

## Acessibilidade

Os diálogos prendem o foco e fecham com Esc, e o foco volta ao ponto de origem. Os rótulos ficam visíveis, e o estado é dito em texto além da cor. Toda a navegação funciona pelo teclado, com foco visível.

## Integração com a API

- O app do administrador usa só `/api/v1/admin/...`, com o cookie de renovação em `Path=/api/v1/admin/auth/refresh`. Ele roda no mesmo site da API e do app do convidado (local: `localhost:3001`), por causa do `SameSite=Strict`.
- A lista de feedback mostra o `tenantName` que vem em cada item. No estado do sistema, cada parte com `status: UNKNOWN` aparece como "desconhecido" com o motivo, nunca zero.
- O app do convidado usa só `POST /api/v1/feedback`.
- Cada app tem tipos gerados da sua própria especificação. O código do convidado não contém operações administrativas (ADR-009).
