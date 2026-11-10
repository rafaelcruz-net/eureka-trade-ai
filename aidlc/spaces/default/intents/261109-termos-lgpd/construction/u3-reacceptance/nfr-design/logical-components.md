# Componentes lógicos — U3 `u3-reacceptance`

Conversa: português (Brasil). Mostra onde cada padrão de NFR da U3 se aplica.

| Componente | Módulo | Papel | Falha e alcance |
|---|---|---|---|
| `TermsReacceptanceFilter` (`FilterOrder.TERMS_REACCEPTANCE` = +16) | Identity (web) | trava das rotas do convidado (BR3.1, BR3.2) | com o banco indisponível, `503`; só requisições de convidado |
| `TermsAcceptanceLookup` (porta) e `JdbcTermsAcceptanceLookup` | Identity | consulta `EXISTS` do aceite da vigente | igual |
| `TermsAcceptanceController` e `TermsAcceptanceService` | Identity | `POST /api/v1/terms/acceptances` (L2) | `409` para versão superada; idempotente |
| `ReacceptanceProperties` | Identity | `memetrade.terms.reacceptance.allowed-prefixes` | configuração |
| `AccountQueryService` (ampliado) | Identity | `terms: TermsStatus` na Conta | igual à Conta de hoje |
| `ReacceptanceScreen`, `TermsConsentBoxes`, `LegalSummary`, `TermsStatusPanel` | GuestWebApp | telas T7, convite e Conta | estado de erro em texto |
| Cliente de API do app (ampliado) | GuestWebApp | redireciona no `403` com o código | todas as telas do convidado |
| `scripts/load/reacceptance.mjs` | scripts | cenário de carga do aceite | manual |

## Recursos compartilhados

| Recurso | Dono | Quem usa | Fronteira |
|---|---|---|---|
| Lista de prefixos liberados | U3 (Identity) | U4 acrescenta `/api/v1/privacy/` | só a configuração |
| `terms_acceptance` | Identity | U5 (anonimização mantém os aceites sem vínculo) | só pelo Identity |
