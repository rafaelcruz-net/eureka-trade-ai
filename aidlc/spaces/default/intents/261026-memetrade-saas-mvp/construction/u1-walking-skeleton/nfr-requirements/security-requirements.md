# Requisitos de segurança — U1 Esqueleto de ponta a ponta

Requisitos de segurança da Unit `u1-walking-skeleton`, derivados das regras de [rules.md](../functional-design/rules.md) (BR1 a BR4) e dos fluxos de [functional-spec.md](../functional-design/functional-spec.md), dos NFR4 a NFR7 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos contratos C1, C9 e C10 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das regras do `project.md`. Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q2, Q4, Q7, Q8).

## Classificação dos dados da U1

| Dado | Classe | Onde aparece | Proteção |
|---|---|---|---|
| Endereço público de carteira | Confidencial (tratado como dado pessoal, A5) | Wallet, LoginChallenge, trilha de auditoria | Completo só no banco e na trilha; mascarado (4 + 4 caracteres) em logs, métricas e respostas fora da conta |
| Token de acesso | Restrito | Só na memória do app e no cabeçalho `Authorization` | Nunca gravado no servidor nem em log |
| Token de renovação | Restrito | Cookie `HttpOnly`, `Secure`, `SameSite=Strict` | Só o resumo criptográfico é gravado (BR1.15) |
| Nonce e assinatura de login | Restrito no trânsito; o nonce é público na mensagem | Pedido de verificação | Nunca em log nem na trilha (BR4.3); o nonce guardado no desafio não é segredo (BR1.10) |
| Dados de mercado simulados | Público | Lista do scanner | Sem restrição |
| Eventos de auditoria | Confidencial | Tabela de saída e trilha | Append-only; sem segredos |

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR6.1 | Nenhum campo, tela ou API aceita frase secreta, mnemônica ou chave privada | Teste do contrato gerado sem esses campos; requisição com campo extra recusada com `VALIDATION_FAILED` e nada gravado (BR1.8) | NFR6, FR1.5 |
| NFR6.2 | Login resistente a repetição | Nonce aleatório de pelo menos 128 bits, uso único, expiração de 5 minutos; testes das fronteiras 4:59 e 5:01 e de duas verificações simultâneas (BR1.2, BR1.3) | NFR6 |
| NFR6.3 | Assinatura conferida com Ed25519 nativo do Java 21 | O código de produção não depende de SDK Solana nem de biblioteca de criptografia externa para o login; a decodificação Base58 tem testes com vetores conhecidos e entradas inválidas | Q4, Q8 |
| NFR6.4 | Nenhuma API de geração ou importação de chave no código de produção | O SDK Solana para Java fica só no escopo de teste; uma verificação da CI falha o build se o código de produção depender do SDK ou chamar geração de par de chaves ou importação de mnemônica | `project.md` (Forbidden), Q8 |
| NFR6.5 | Limite de tentativas sem bloqueio por terceiros | 10 pedidos por minuto por IP no desafio e na verificação; por carteira, só verificações recusadas contam e só para os IPs que as fizeram; resposta `429` com `Retry-After` (BR1.11); contadores na memória do processo de API (Q2) | NFR6, Q8 do design funcional |
| NFR6.6 | Sessão que nunca autoriza trading e é conferida a cada requisição | Token de acesso de 15 min com identificador da sessão; cada requisição confere sessão, usuário e tenant ativos (BR1.14); renovação troca o token e revoga a sessão em reutilização (BR1.15) | NFR6, FR1.4 |
| NFR6.7 | Segredos fora do repositório | `.env` local ignorado pelo git; `.env.example` sem valores; Gitleaks no pré-commit e na CI bloqueando qualquer segredo; varredura única antes do primeiro commit | NFR6, `team.md` |
| NFR6.8 | Portões de segurança na CI | CodeQL (SAST) e OWASP Dependency-Check (dependências) em todo pedido de merge; achado Crítico ou Alto bloqueia; Médio avisa; exceções em arquivo versionado com validade de até 90 dias | NFR6, `team.md`, Q3 |
| NFR6.9 | Cabeçalhos e configuração segura da API | CORS só com a origem do app do convidado e credenciais só em verificação, renovação e saída; cabeçalhos `X-Content-Type-Options`, `X-Frame-Options` e política de conteúdo no app; erros em `ProblemDetail` sem detalhe interno | NFR6, C1 |
| NFR5.1 | Tenant e pessoa só do contexto de autenticação | Teste: a lista chamada com `tenantId` na query ignora o valor (BR2.1) | NFR5, FR2.1 |
| NFR5.2 | Registro de rotas de dado de cliente com teste negativo obrigatório | Teste que lê o registro de rotas e falha o build se uma rota de cliente não tiver teste negativo entre tenants (BR2.3) | NFR5 |
| NFR7.1 | Endereços mascarados fora da trilha | Varredura dos logs e métricas dos testes sem nenhum endereço completo; formato 4 + 4 caracteres | NFR7 |
| NFR4.1 | Repetição segura do desafio | Mesma `Idempotency-Key` e mesmo conteúdo devolvem o mesmo desafio até a expiração; conteúdo diferente devolve `409`; a verificação não guarda resposta (BR1.10) | NFR4, C1 |
| NFR7.2 | Proteção em trânsito e em repouso | No ambiente local: HTTP em `localhost` (contexto seguro do navegador) e sem criptografia de disco. Antes de qualquer ambiente compartilhado (G1): TLS 1.2 ou superior em toda conexão externa e criptografia em repouso do banco e dos backups | NFR7, Q7 |

## Modelo de ameaças (STRIDE) dos fluxos da U1

| Ameaça | Exemplo | Mitigação | Requisito |
|---|---|---|---|
| Falsificação | Reaproveitar uma assinatura de login capturada | Nonce de uso único, expiração, mensagem idêntica à emitida | NFR6.2 |
| Falsificação | Entrar como administrador pela entrada do convidado | Carteiras de administrador da configuração recebem 403 (BR1.5) | NFR6.6 |
| Adulteração | Alterar domínio, chain ou nonce na mensagem | Comparação exata com a mensagem guardada; recusa consome o nonce (BR1.4) | NFR6.2 |
| Repúdio | Negar uma tentativa de login | Evento `LOGIN_SUCCEEDED` ou `LOGIN_REJECTED` gravado na mesma transação (BR1.6, BR4.1) | NFR6.2 |
| Divulgação | Token ou nonce em log | Proibição de segredos em logs e eventos e varredura automática (BR4.3) | NFR7.1 |
| Divulgação | Endereço completo exposto em métricas | Mascaramento fora da trilha | NFR7.1 |
| Negação de serviço | Inundar o desafio com o endereço de outra pessoa | Limite por IP; limite por carteira só sobre recusas do mesmo IP | NFR6.5 |
| Elevação de privilégio | Usar a sessão de login para autorizar operações | Sessão nunca autoriza trading; não existe rota REAL | NFR6.6 |
| Elevação de privilégio | Ler dado de outro tenant mandando `tenantId` | Tenant só do token | NFR5.1 |

## Conformidade

- **LGPD:** endereço de carteira tratado como dado pessoal até parecer em contrário (A5); retenção mínima definida depois do inventário de dados (SP-3). Na U1 só existem contas semeadas de teste.
- **Auditoria:** toda ação sensível da U1 tem exatamente um evento, entregue pela tabela de saída (C9).
- **REAL desligado:** nenhum caminho de assinatura ou envio de transação existe na U1 (FR15.2).
