# Design de segurança — U1 `u1-legal-documents`

Conversa: português (Brasil). Atende `construction/u1-legal-documents/nfr-requirements/security-requirements.md` (NFR1.1, NFR1.2, NFR6.1 a NFR6.6). Decisões em `nfr-design-questions.md` (Q1).

## Controles

| Controle | Design | Requisito |
|---|---|---|
| Rotas públicas | Na configuração de segurança da API, `GET /api/v1/legal/**` dispensa autenticação; os outros métodos nessas rotas respondem 405 | NFR6.2 |
| CORS | Origens permitidas = as dos dois apps, só `GET` e `HEAD`, sem credenciais, sem cabeçalhos expostos além de `ETag` | NFR6.2 |
| Limite de requisições | `LegalDocumentsRateLimiter` novo, no padrão do limitador de consulta de convite, com contadores próprios: janela de 1 minuto, padrão 60 por IP do cliente (resolvido pelo `ClientIpResolver` existente), `memetrade.legal.rate-limit-per-minute` (alto no perfil `loadtest`). Excedeu: `429 RATE_LIMITED` com `Retry-After` | NFR6.3 |
| Isenção interna | `memetrade.legal.rate-limit.internal-networks` (lista CIDR) comparada **só com o endereço do par direto da conexão**, nunca com o IP resolvido nem com `X-Forwarded-For`. A lista não inclui balanceadores nem proxies confiáveis. Local: rede do Compose; produção: vazia até a rede da nuvem existir | NFR6.3 (Q1) |
| Texto sem marcação | A API devolve parágrafos como texto. O app renderiza só como texto (nunca como HTML injetado). Teste com `<script>` no texto | NFR6.4 |
| Cabeçalhos | API: `SecurityHeadersFilter` existente, com o `no-store` sobrescrito só nas respostas 200 e 304 das rotas de documentos. Apps: política de conteúdo com nonce, `X-Content-Type-Options` e proteção contra enquadramento, que já existem; `/termos` e `/privacidade` entram como rotas públicas no controle de acesso dos dois apps | NFR6.5 |
| Configuração no cliente | Origem da API e campos de implantação só no servidor; nenhum `NEXT_PUBLIC_*` | NFR6.1 |
| Ativação | Uma versão ignorada não é encontrada pelo aceite nem pela busca (BR1.16) | NFR6.6 |
| Logs | O aviso de versão ignorada traz só a versão e o motivo; nunca o texto nem os campos de implantação | NFR1.1 |
| Encarregado | O e-mail aparece como texto e `mailto:`, sem formulário | NFR1.2 |

## Testes de segurança

- O IP externo passa do limite e recebe `429`.
- Um `X-Forwarded-For` com IP interno forjado, vindo de IP externo, também recebe `429`.
- O par direto da rede interna configurada não recebe `429`.
- `POST` nas rotas de documentos recebe 405.
- Texto com `<script>` aparece literalmente na página.
- Os cabeçalhos de cache variam por status (200 e 304 com cache; 404 e 429 com `no-store`).
- `/termos` abre sem sessão nos dois apps.
