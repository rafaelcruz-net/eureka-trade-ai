## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-04T03:01:44Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/performance-requirements.md > NFR3.2 (carga) versus security-requirements.md > NFR6.6 | O critério de NFR3.2 pede 20 usuários simultâneos com uma ação a cada 2 s (cerca de 600 consultas por minuto) em `GET /api/v1/invites/{code}`. O limite de NFR6.6 aceita 10 consultas por minuto por IP, e o teste de carga local sai de um único IP. Quase todas as consultas receberiam `429`, então o p95 de 300 ms não mede a consulta de verdade e o critério não é executável como está escrito. | Dizer como o teste de carga contorna o limite (IPs distintos por usuário simulado, ou limite configurável só no perfil de teste de carga), ou separar a meta de 300 ms do trecho sem `429`. Registrar a escolha em NFR3.2. | New |
| R-02 | Medium | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/security-requirements.md > NFR6.3 a NFR6.5 (código do convite) | Nenhum requisito proíbe cache da resposta `201` da emissão, que traz o código completo, nem das respostas de `GET /api/v1/invites/{code}` e `GET /api/v1/account`. Também não há requisito para os logs de acesso do app web (Next.js) e de proxy, que veem a URL da página do convidado com o código no caminho. NFR6.4 cobre só os logs da API e `no-referrer` cobre só o cabeçalho Referer. O código é uma credencial de uso único que fica exposta nesses pontos. | Acrescentar `Cache-Control: no-store` à resposta da emissão, à consulta do convite e à tela Conta, com teste de cabeçalho. Estender a varredura de logs (NFR6.4) ao app web, ou exigir que a rota da página use modelo sem o código nos logs. | New |
| R-03 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/observability-requirements.md > NFR9.1 versus reliability-requirements.md > NFR10.3 | NFR10.3 impede a API de subir sem termos vigentes. NFR9.1 faz o indicador de saúde ficar `DOWN` quando os termos não foram carregados. Como o arquivo só é lido na subida, o estado que NFR9.1 descreve não pode ocorrer e o critério `DOWN` não é testável. | Remover a condição dos termos de NFR9.1, ou dizer que os termos podem ser recarregados em execução e testar esse caso. | New |
| R-04 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/security-requirements.md > NFR6.11 | A lista de entradas de teste do redirecionamento não inclui caracteres de controle que navegadores removem, como `/<tab>/exemplo.com` ou `/<LF>/exemplo.com`, que viram `//exemplo.com`. O teste também não confere que o valor aceito é uma rota do convidado, o que BR4.2 exige. | Acrescentar esses casos e um caso de caminho interno que não é rota do convidado ao teste de NFR6.11. | New |
| R-05 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/security-requirements.md > NFR6.6 e NFR6.7 | Não está dito se a verificação de formato do código (NFR6.7) roda antes ou depois do limite por IP (NFR6.6), nem se códigos mal formados contam no contador. Quem implementa precisa adivinhar, e a ordem muda o que um atacante observa (`404` ou `429`). | Fixar a ordem (por exemplo, limite primeiro, depois formato) e dizer que consultas mal formadas também consomem o contador. | New |
| R-06 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/security-requirements.md > NFR7.2 (e Q3) | O NFR7 da inception exige política de retenção aprovada antes dos convites. A U3 só diz que a regra "sai do inventário (SP-3), aprovado antes dos convites reais", sem critério verificável nem ponto de controle, e a retenção por tempo indefinido (Q3, opção A) fica sem prazo. | Nomear a verificação de liberação (por exemplo, SP-3 aprovado como condição antes da emissão do primeiro convite real) ou registrar quem confere isso. | New |
| R-07 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/security-requirements.md > NFR6.1 e NFR4.5 | A frase "o gerador vem do relógio e do aleatório injetáveis só nos testes" é ambígua: abre a possibilidade de uma fonte de aleatoriedade substituível em produção. NFR4.5 diz que 100% das emissões e revogações exigem chave de idempotência, mas não diz o que acontece sem a chave. | Reescrever NFR6.1 dizendo que a produção usa só `SecureRandom` e que a injeção existe só em teste. Em NFR4.5, definir a resposta sem chave (por exemplo, `400 VALIDATION_FAILED`, nada gravado). | New |
| R-08 | Low | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u3-identity-access/nfr-requirements/security-requirements.md > IDs NFR6.5, NFR3.1 e NFR3.2 | Os IDs da U3 repetem os da U1 com significados diferentes (NFR6.5 é limite de tentativas na U1 e `Referrer-Policy` na U3; NFR3.1 é a API da U1 e a verificação com convite na U3). Referências soltas a "NFR6.5" ficam ambíguas. | Citar sempre a Unit junto do ID nas referências cruzadas, ou prefixar os IDs da U3. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Validade de `traceability.json` | PASS | JSON válido. |
| Cobertura de NFR1 a NFR14 | PASS | As 14 linhas existem. NFR1 e NFR8 estão como N/A, com justificativa de dono em outra Unit. |
| Alvos `NFRx.y` marcados como OK | PASS | Todos os IDs de alvo existem nos arquivos da Unit (NFR2.1 a 2.4, 3.1 a 3.4, 4.1 a 4.5, 5.1 e 5.2, 6.1 a 6.13, 7.1 e 7.2, 9.1 a 9.6, 10.1 a 10.6, 11.1, 12.1, 13.1, 14.1). |
| Consistência com respostas Q1 a Q5 | PASS | Q1 A (500/300 ms), Q2 A (SHA-256 simples), Q3 A (guardar tudo), Q4 B (sem limite administrativo) e Q5 B (sem métricas de negócio) aparecem como respondidas. O orçamento da verificação soma 500 ms. |
| Consistência com BR1.8, BR2.2, BR3.6 e BR4.2 | PASS | NFR6.3, NFR6.6, NFR6.9 e NFR6.11 reproduzem as regras. A única lacuna está em NFR6.11 (casos de teste) e na ordem entre NFR6.6 e NFR6.7. |

### Summary

Não encontrei defeito que bloqueie a implementação: os requisitos seguem as respostas confirmadas, o design funcional e as regras do projeto, e a cobertura dos NFR1 a NFR14 está completa. As ressalvas mais relevantes são o teste de carga de NFR3.2 que colide com o limite de NFR6.6 e a ausência de requisito de não-cache e de log de acesso para o código do convite. Ambas se corrigem com ajustes pontuais antes da geração de código.
