# Componentes lógicos — U8 Administração e feedback

| Componente | Processo | Responsabilidade | Domínio de falha e impacto |
|---|---|---|---|
| `administration` (módulo Java) | api | Kill switch (`KillSwitchService`, `KillSwitchApplier`, `ApplyLock`, `KillSwitchReconciler`), estado do sistema (`SystemStatusAssembler`), rotas `/api/v1/admin/kill-switch`, `/system-status` e `/tenants` | Se falhar, o kill switch não muda de estado, mas a U5 mantém a própria cópia (`SystemPauseState`); a reconciliação corrige depois. O estado do sistema degrada por parte |
| `feedback` (módulo Java) | api | `POST /api/v1/feedback`, `GET /api/v1/admin/feedback`, expurgo diário | Se falhar, só o feedback fica indisponível (503), e o texto continua no cliente |
| Entrada do administrador (`identity`, estendido pela U8) | api | `AdminAuthController`, `AdminLoginLimiter`, filtro de papel inverso | Se falhar, o administrador não entra; o convidado não é afetado |
| `apps/admin` (Next.js) | admin (contêiner novo) | Telas A1, A2, kill switch, feedback e entrada | Se cair, só o administrador fica sem tela; a API continua |
| Diálogo de feedback (`apps/web`) | web | S9 no cabeçalho | Fica restrito ao diálogo |
| Banco | postgres | `kill_switch_state`, `kill_switch_change`, `feedback_entry`; trava consultiva | Compartilhado; a U8 usa uma conexão a mais para a trava |

## Recursos compartilhados

- **Interfaces:** `BotAdministration` (U5), `IdentityAdministration` (U3), interface de dados de mercado (U4), trilha (U2) e a nova `RejectionCounts.countRejectedSince` (U6).
- **Infraestrutura comum:** o mecanismo comum de idempotência e os filtros de autenticação, correlação e papel da identidade.

## Isolamento

- O app do administrador não compartilha código nem tipos com o `apps/web`.
- O worker não roda nada da U8.
- Os trabalhos da U8 rodam só no processo de API (`@ProcessRole(API)`).
