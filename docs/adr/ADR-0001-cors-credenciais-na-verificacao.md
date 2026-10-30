# ADR-0001 — CORS com credenciais também na verificação do login

- **Status:** aceito (geração de código da U1, 2026-10-28)
- **Contrato afetado:** C1 — Autenticação e sessão do convidado ([contract-summary.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/contract-design/contract-summary.md), regra comum "CORS")
- **Origem da decisão:** Q7 do NFR Design ([security-design.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/nfr-design/security-design.md))

## Contexto

O contrato C1 dizia que a API permite credenciais (cookies) no CORS só nas rotas de renovação e de saída. Mas a resposta de `POST /api/v1/auth/verify` é a que entrega o cookie de renovação (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth/refresh`) em `Set-Cookie`. O app do convidado (porta 3000) e a API (porta 8080) são origens diferentes no mesmo site. O navegador só guarda um cookie vindo de outra origem quando o pedido sai com `credentials: 'include'` e a resposta traz `Access-Control-Allow-Credentials: true`. Sem isso, o login funcionaria, mas a renovação ao recarregar a página (F2) nunca teria cookie.

## Decisão

A lista de rotas com credenciais no CORS passa a ser: `POST /api/v1/auth/verify`, `POST /api/v1/auth/refresh` e `POST /api/v1/auth/logout`. As demais rotas continuam sem credenciais. A origem permitida continua sendo só `GUEST_APP_ORIGIN`.

Implementação: `apps/api/src/main/java/com/memetrade/identity/web/GuestCorsPolicy.java`, coberta por `GuestCorsPolicyTest`.

## Consequências

- O cookie de renovação é gravado no login e a sessão sobrevive ao recarregar a página.
- A verificação passa a aceitar cookies de outra origem do mesmo site. O risco de CSRF fica contido porque a verificação exige uma assinatura de carteira sobre um nonce de uso único, o cookie é `SameSite=Strict` e a lista de origens é fixa.
- O bloco C1 do contrato e a regra comum "CORS" devem citar este ADR.

## Alternativas rejeitadas

| Alternativa | Por que foi rejeitada |
|---|---|
| Devolver o token de renovação no corpo e o app gravá-lo | Expõe o token ao JavaScript; viola "token só na memória e renovação por cookie HttpOnly". |
| Proxy da API pelo Next.js (mesma origem) | Mais uma peça na rota de cada pedido e no limite de tentativas (IP do proxy); a infraestrutura definiu o navegador chamando a API direto. |
| Pedir a renovação logo depois do login para obter o cookie | A renovação precisa do próprio cookie; não resolve. |
