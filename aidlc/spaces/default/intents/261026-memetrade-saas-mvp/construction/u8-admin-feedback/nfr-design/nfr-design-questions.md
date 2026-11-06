# Perguntas do desenho não funcional — U8 Administração e feedback

Unit `u8-admin-feedback`. Base: [requisitos não funcionais](../nfr-requirements/), [desenho funcional](../functional-design/functional-spec.md) e os achados abertos das revisões:
- desenho funcional: R-09 (restos do texto antigo do kill switch) e R-10 (espera pela trava);
- requisitos não funcionais: R-01 a R-07.

Já decidido sem pergunta:
- O `PUT` do kill switch exige `Idempotency-Key` e usa o mecanismo comum da identidade (R-03).
- O contraste de texto fica ≥ 4,5:1 no tema escuro do app do administrador (R-05).
- O cookie de renovação é `HttpOnly`, `Secure` e `SameSite=Strict` (R-07).

## Q1. [Confiabilidade] Qual trava serializa as chamadas do kill switch à U5?

A trava única de aplicação (BR2.8) precisa durar toda a chamada à U5. Se ela vencer no meio da chamada, um OFF pode ser desfeito.

A. Trava consultiva de sessão do PostgreSQL (`pg_try_advisory_lock` com chave fixa), numa conexão própria e mantida durante a chamada. Ela não tem prazo de validade: só é solta quando a chamada termina ou quando a conexão cai (por exemplo, se o processo morrer). Quem espera tenta de novo a cada 200 ms por até 10 s. Depois da chamada, quem tem a trava relê `version`; se mudou, aplica de novo a operação do estado atual (até 3 vezes).
B. Linha de trava no estilo do ShedLock, com prazo máximo de 60 s.
X. Other (please specify)

[Answer]: A

## Q2. [Desempenho] Tempo máximo para todos os bots ficarem pausados

O revisor apontou que os 35 s do NFR2.2 não contam a espera de até 10 s pela trava nem o reinício do processo.

A. A meta passa a 45 s no pior caso: 5 s de resposta, mais 10 s de espera pela trava, mais 30 s até a próxima reconciliação. A reconciliação também roda uma vez ao subir o processo de API. No caminho normal (sem espera nem queda), a meta continua 5 s.
B. Manter 35 s e reduzir o intervalo da reconciliação para 20 s.
X. Other (please specify)

[Answer]: A

## Q3. [Segurança] Política de segurança de conteúdo com o Next.js

Sem `unsafe-inline`, os scripts de hidratação do Next.js precisam de nonce ou hash.

A. Nonce por requisição gerado no middleware do `apps/admin`: `script-src 'self' 'nonce-<valor>' 'strict-dynamic'`; `style-src 'self' 'unsafe-inline'` (só estilos); `connect-src 'self'` mais a origem da API; `frame-ancestors 'none'`; `base-uri 'none'`; `object-src 'none'`. As páginas são renderizadas por requisição.
B. Páginas estáticas com hashes dos scripts calculados no build.
X. Other (please specify)

[Answer]: A

## Q4. [Segurança] Limite de tentativas na entrada do administrador

A entrada do convidado aceita 10 tentativas por minuto por IP (`login-rate-limit-per-minute: 10`).

A. A entrada do administrador tem um limite próprio e mais estrito: 5 desafios por minuto por IP e 5 por minuto por endereço. Acima disso, a resposta é `429 RATE_LIMITED` com `Retry-After`, gravada como `LOGIN_REJECTED` com o motivo `RATE_LIMITED`. Há teste para os dois limites.
B. Usar exatamente o limite do convidado (10 por minuto por IP).
X. Other (please specify)

[Answer]: A

## Q5. [Desempenho] Como medir o NFR3.1 (telas do administrador com p95 ≤ 2 s)

A. Duas medições:
   - um teste de integração faz 20 leituras simultâneas de cada rota administrativa (estado, convites, kill switch, feedback) e confere o p95 ≤ 2 s;
   - o smoke mede cada tela do administrador abrindo em até 2 s com um usuário.
B. Deixar para um cenário de carga futuro.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

[Answer]: Looks correct
