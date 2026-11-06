# Requisitos de segurança — U8 Administração e feedback

Base: [respostas](nfr-requirements-questions.md) Q3 e Q4, [regras](../functional-design/rules.md) BR1 a BR6, NFR4 a NFR7 de `requirements.md` e as regras do `project.md`.

## Classificação dos dados da U8

| Dado | Classe | Tratamento |
|---|---|---|
| Endereço da carteira do administrador | Pessoal | Mascarado (4 + 4) em log e métrica; completo só na trilha |
| Nonce e assinatura da entrada | Segredo de sessão | Nunca em log, métrica ou evento |
| Token de acesso e cookie de renovação | Segredo de sessão | Nunca em log; o cookie é `HttpOnly` |
| Motivo do kill switch | Interno | Em evento e histórico; não vai para log |
| Texto do feedback | Pessoal (livre) | Só no banco e na tela do administrador; apagado em 180 dias |
| Contagens do estado do sistema | Interno, agregado | Sem identificadores |

## Requisitos

| ID | Requisito | Meta e verificação | Origem |
|---|---|---|---|
| NFR4.1 | Histórico do kill switch só de acréscimo | `UPDATE` e `DELETE` em `kill_switch_change` falham para o papel da aplicação. Teste com o papel da aplicação | BR2.9, FR3.3 |
| NFR4.2 | Envio de feedback sem duplicar | A mesma `Idempotency-Key` com o mesmo corpo devolve o mesmo 201 e grava uma linha; corpo diferente dá 409. Respostas 400 e 429 não ficam guardadas. Teste obrigatório de deduplicação | BR6.4 |
| NFR4.3 | Limite de feedback sem corrida | Com 20 envios simultâneos da mesma pessoa que já tem 0 na janela, exatamente 10 são aceitos e 10 recebem 429 (50 repetições) | BR6.3, Q6 |
| NFR5.1 | Feedback isolado por tenant | `tenantId` e `userId` vêm só do token; valores no corpo são ignorados. A lista do administrador nunca traz `userId` nem carteira. Teste negativo multi-tenant | BR6.2, BR6.6, NFR5 |
| NFR5.2 | Administrador sem acesso a dado de pessoa | Token ADMIN em cada rota de bot, ledger, P&L, operações, recusas e carteira responde 403 `FORBIDDEN_ROLE`, com um `REQUEST_FORBIDDEN`. Teste que percorre o registro de rotas do convidado | AC7.4.2, BR4.1 |
| NFR6.1 | Entrada só pela carteira configurada | Desafio só para `ADMIN_WALLET_ADDRESSES`; nonce de uso único com expiração ≤ 5 min; Ed25519 nativo do Java (sem SDK Solana na produção). Testes de nonce reutilizado, vencido, assinatura inválida, carteira fora da lista e carteira de convidado (`WALLET_IS_GUEST`) | BR1.1, BR1.2, NFR6 |
| NFR6.2 | Sessão curta | Token de 15 min, sessão de 8 h sem prorrogação, cookie em `Path=/api/v1/admin/auth/refresh` com rotação e recusa de reutilização. Carteira retirada da lista perde o acesso na renovação seguinte | BR1.3, BR1.4, Q1 do desenho |
| NFR6.3 | Cada token na sua área | Token BETA_USER em `/api/v1/admin/...` e token ADMIN fora dela: 403 com `REQUEST_FORBIDDEN`, um evento por recusa, caminho normalizado antes da comparação | BR1.5, AC7.1.4, AC7.2.5 |
| NFR6.4 | App do administrador isolado | Serviço e imagem próprios (`admin`, porta 3001), no mesmo site da API. Política de segurança de conteúdo: `default-src 'self'`, `connect-src` só a API, sem `unsafe-eval` e sem scripts de terceiros. Teste que confere o cabeçalho | Q3, ADR-009 |
| NFR6.5 | Convidado sem código administrativo | Um teste da CI procura `/api/v1/admin` no pacote gerado do `apps/web` e falha se achar | Q3, ADR-009 |
| NFR6.6 | Limite de tentativas na entrada administrativa | A mesma política de limite por endereço e por IP da entrada do convidado | Q3 |
| NFR6.7 | Nada sensível em log | A varredura dos logs nos testes e no smoke não encontra nonce, assinatura, token, cabeçalho `Authorization`, código de convite nem texto de feedback | NFR6, project.md |
| NFR7.1 | Texto do feedback protegido | O texto nunca vai para log, métrica nem evento; só o tamanho e a tela. Apagado em 180 dias pelo expurgo diário (BR6.7). Teste do expurgo com relógio controlado | Q4, BR6.7, NFR7 |
| NFR7.2 | Carteira mascarada | O endereço do administrador aparece mascarado em logs e métricas e na resposta do kill switch (`changedBy`); completo só na trilha | NFR7, BR2.7 |

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Convidado usa o próprio token na API administrativa | NFR6.3 |
| Administrador lê dados de uma pessoa | NFR5.2 |
| Reuso de assinatura de entrada | NFR6.1 |
| Roubo de token pelo navegador | Token só na memória, cookie `HttpOnly` `SameSite=Strict`, política de segurança de conteúdo (NFR6.4) |
| HTML malicioso no feedback | Exibido como texto (BR6.6) e protegido pela política de segurança de conteúdo |
| Kill switch desfeito por corrida | Trava única de aplicação e reconciliação (BR2.5, BR2.8) |
| Abuso do feedback | Limite de 10 por hora (NFR4.3) |
