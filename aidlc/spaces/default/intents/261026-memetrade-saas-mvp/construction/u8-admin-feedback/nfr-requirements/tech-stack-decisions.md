# Decisões de tecnologia — U8 Administração e feedback

Base: resposta Q7 e o layout do `team.md`. A U8 não acrescenta linguagem nem banco novos.

| Decisão | Escolha | Motivo | Alternativa rejeitada |
|---|---|---|---|
| App do administrador | `apps/admin`, um novo app Next.js no mesmo workspace pnpm, com a mesma versão do Next.js, do React, do TypeScript (`strict`) e do Node do `apps/web` | Separação exigida pelo ADR-009, sem nova ferramenta para manter | Rotas administrativas dentro do `apps/web`, rejeitada pelo ADR-009 |
| Componentes compartilhados | Nenhum pacote compartilhado por enquanto; o pouco que se repete é copiado | Evita acoplar os dois apps cedo (Q7) | Pacote de UI compartilhado, para depois do beta |
| Tipos da API | Gerados só de `apps/api/openapi/openapi-admin.yaml`, como o `apps/web` faz com a do convidado | Contrato único; quebra de contrato falha a CI | Tipos escritos à mão |
| Testes do frontend | Os mesmos do `apps/web` (Jest, Testing Library, Playwright), com piso de 80% medido no `apps/admin` separadamente | NFR12 por app | — |
| Backend | Módulos novos `administration` e `feedback` no Spring Boot existente, embutidos no processo de API; migração Liquibase nova; ShedLock para os trabalhos | Monólito modular já decidido | Serviço separado |
| Trava de aplicação do kill switch | Trava distribuída sobre o PostgreSQL (a mesma família do ShedLock ou trava consultiva), mantida durante a chamada à U5 | Sem infraestrutura nova; o banco já é a fonte de verdade | Fila ou serviço de trava externo |
| Assinatura | Ed25519 nativo do Java e Base58 próprio, como na U1 | Proibição do SDK Solana na produção (`project.md`) | SDK Solana |
| Contêiner | Imagem própria `memetrade-admin`, serviço `admin` no Docker Compose, porta 3001 em `127.0.0.1` | Mesmo site da API, isolamento do convidado | Servir pelo mesmo contêiner do `web` |
