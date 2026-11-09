# Decisões de tecnologia — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `construction/u1-legal-documents/functional-design/`, `inception/requirements-analysis/requirements.md`, `inception/contract-design/contract-summary.md` e a base de conhecimento do código. Nada novo é introduzido; a U1 usa a stack existente.

## Decisões

| Área | Escolha | Motivo |
|---|---|---|
| API | Java 21, Spring Boot, módulo `identity` (catálogo existente ampliado) | já é dono dos termos; sem dependência nova |
| Catálogo | pastas de versão no classpath, lidas uma vez na subida | igual à v1; imutável por implantação |
| Cache HTTP | `Cache-Control` e `ETag` pelo Spring MVC | sem infraestrutura nova |
| Limite de tentativas | limitador novo das rotas de documentos, no padrão do limitador de consulta de convite, com contadores e propriedade próprios e isenção da rede interna dos apps | não existe limitador para essas rotas; o padrão existente é reaproveitado sem compartilhar contadores |
| Saúde e métrica | Spring Actuator e Micrometer existentes | padrão do produto |
| Páginas | Next.js nos dois apps, montadas no servidor com reaproveitamento por 5 min | padrão do produto; sem segredo no cliente |
| Contrato | OpenAPI gerado e tipos gerados nos apps | regra do time |
| Testes | JUnit 5 e Testcontainers, Jest, Playwright e o cenário `legal` no script de carga | regra do time |

Nenhuma biblioteca nova; nenhuma biblioteca de Markdown nem de sanitização de HTML, porque o texto nunca vira marcação.
