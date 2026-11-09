# Requisitos de segurança — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes:
- `construction/u1-legal-documents/functional-design/` (BR1.4, BR1.7, BR1.16);
- `inception/requirements-analysis/requirements.md` (NFR1, NFR6);
- `inception/contract-design/contract-summary.md` (L1);
- `nfr-requirements-questions.md` (Q2, Q5);
- as regras do projeto: nenhum segredo em `NEXT_PUBLIC_*`, nada de dado pessoal em log.

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | Nenhum log ou resposta da U1 contém dado pessoal. O aviso de versão ignorada traz só a versão e o motivo, nunca o texto do documento | teste de log |
| NFR1.2 | O e-mail do encarregado é exibido como texto e link `mailto:`, sem formulário e sem coleta de dado | teste de componente |
| NFR6.1 | Os campos de implantação são configuração, não segredo. Nenhum segredo nem variável `NEXT_PUBLIC_*` nas páginas; a origem da API é lida no servidor | revisão de código e varredura existente |
| NFR6.2 | As rotas de documentos são só leitura e públicas. Outros métodos são recusados; o CORS aceita só as origens dos dois apps, sem credenciais | teste de integração |
| NFR6.3 | Limitador novo para as rotas de documentos, no mesmo padrão do limitador de consulta de convite, mas com contadores próprios: 60 requisições por minuto por IP do cliente (propriedade própria, padrão 60, valor alto no perfil `loadtest`), com `429 RATE_LIMITED` e `Retry-After` (Q2). As chamadas que o servidor web dos apps faz para montar as páginas ficam isentas. A isenção vale por origem de rede interna configurada (a rede do Compose ou da VPC), nunca por cabeçalho enviado pelo cliente. Essas chamadas são raras, porque o dado é reaproveitado por 5 minutos (NFR3.4) | teste de integração com limite baixo: o IP externo recebe `429` e a origem interna configurada não |
| NFR6.4 | O texto é sempre escapado e nunca interpretado como marcação: nada de injeção de HTML ou script vinda do catálogo | teste de componente com texto contendo `<script>` |
| NFR6.5 | As páginas mantêm os cabeçalhos de segurança dos apps (política de conteúdo sem scripts de terceiros, `X-Content-Type-Options`, proteção contra enquadramento) e não guardam nada no navegador | teste de ponta a ponta dos cabeçalhos |
| NFR6.6 | Uma versão não revisada ou ignorada nunca é aceita nem exibida como vigente (BR1.16) | teste de integração do aceite |

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Texto do catálogo com marcação maliciosa | NFR6.4 |
| Raspagem ou sobrecarga das rotas públicas | NFR6.3, cache (NFR3.4) |
| Publicar texto não revisado | BR1.1, NFR6.6 |
| Vazar configuração ou segredo no cliente | NFR6.1 |
