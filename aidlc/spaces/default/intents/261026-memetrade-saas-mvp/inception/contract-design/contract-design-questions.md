# Perguntas — Contract Design

Objetivo: fixar os contratos entre as 9 Units de [unit-of-work.md](../units-generation/unit-of-work.md) e o contrato da API usada pelos dois apps web, para que as Units possam ser construídas em paralelo sem retrabalho na integração. Cada aresta de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md) é um contrato candidato.

Já decidido e que não volta a ser perguntado: dentro do backend, chamadas síncronas pela interface pública de cada módulo e eventos em processo para auditoria e painel; entre frontend e backend, REST com tipos gerados do contrato (Units Generation, Q7); rotas sob `/api/v1/`; erros em `ProblemDetail` (RFC 7807) com `correlationId`; repetição só em operação idempotente; paginação por cursor (FR5.1); tenant sempre do contexto de autenticação; fronteiras de módulo por convenção, sem verificação automática por enquanto (`team.md`).

## Q1. [Arquitetura] Quem consome a API REST fora do sistema?

Os únicos clientes previstos são os dois apps Next.js (convidado e administrador). A resposta define se a API precisa de garantias de compatibilidade para terceiros.

A. Ninguém: a API é privada, usada só pelos dois apps do próprio monorepo.
B. A API é privada no beta, mas o contrato já é escrito pensando em parceiros futuros (documentação pública, exemplos, compatibilidade longa).
C. Já existe um consumidor externo previsto no beta (por exemplo, scripts de convidados).
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] De onde nasce o contrato da API?

O `team.md` exige tipos do frontend gerados do contrato e CI falhando em quebra de contrato. Falta decidir se o contrato é escrito primeiro ou extraído do código.

A. Contrato primeiro: um arquivo OpenAPI versionado no repositório é a fonte; o backend é verificado contra ele na CI e os tipos do frontend são gerados dele.
B. Código primeiro: o backend gera o OpenAPI a partir das anotações; a CI compara o arquivo gerado com o versionado e os tipos do frontend são gerados dele.
C. Contrato primeiro, com as interfaces do servidor também geradas do OpenAPI.
X. Other (please specify)

[Answer]: B

## Q3. [Arquitetura] Uma especificação para os dois apps ou duas?

O ADR-009 separa o app do convidado e o do administrador, e as rotas administrativas não podem ir para o código entregue aos convidados.

A. Duas especificações: API do convidado (`/api/v1/...`) e API administrativa (`/api/v1/admin/...`), cada app gerando tipos só da sua.
B. Uma especificação com etiquetas (tags), e cada app gera só as operações da sua etiqueta.
C. Duas especificações servidas em portas ou processos diferentes.
X. Other (please specify)

[Answer]: A

## Q4. [Arquitetura] Como tratar mudanças que quebram o contrato?

Backend e frontends ficam no mesmo monorepo e podem mudar no mesmo commit.

A. Mudança aditiva é livre (clientes ignoram campos novos); mudança que quebra pode ir no mesmo commit, desde que os dois apps sejam regenerados e a CI passe; `/api/v1` só muda quando houver consumidor externo.
B. Toda mudança que quebra cria uma versão nova (`/api/v2`) e as duas convivem até os apps migrarem.
C. Como A, mas toda quebra exige uma nota de mudança registrada no repositório.
X. Other (please specify)

[Answer]: A

## Q5. [Arquitetura] Como `unknown` aparece no JSON?

O critério AC2.1.2 exige distinguir "a fonte informou 0" de "a fonte não informou" no contrato, sem depender de `null` nem de zero. Cada valor de mercado também carrega fonte, horário e frescor.

A. Cada valor de mercado é um objeto com `status` (`KNOWN` ou `UNKNOWN`): quando `KNOWN`, traz `value`, `source`, `observedAt` e `stale`; quando `UNKNOWN`, traz `reason` e, se houver, a fonte consultada.
B. O valor é um texto que pode ser o número ou a palavra `"unknown"`, com fonte e horário em campos ao lado.
C. Os campos ausentes vão numa lista `unknownFields` do token, e o valor some do objeto.
X. Other (please specify)

[Answer]: A

## Q6. [Arquitetura] Como dinheiro e quantidades trafegam no JSON?

Ponto flutuante é proibido para valores monetários, quantidades e P&L, e um número JSON vira ponto flutuante no navegador.

A. Texto decimal com escala fixa por tipo de valor (por exemplo `"123.456789"`), com a regra de escala definida no contrato.
B. Inteiro em unidades base como texto (por exemplo `"123456789"`) mais o número de casas decimais do mint.
C. Os dois: unidades base para quantidades de token e texto decimal para valores em moeda de referência.
X. Other (please specify)

[Answer]: A

## Q7. [Arquitetura] Quais comandos REST exigem chave de idempotência?

Duplo clique em iniciar (AC4.3.3) e em pausar (AC4.8.3) deve gerar um único efeito.

A. Toda requisição que muda estado (POST, PUT, PATCH, DELETE) exige o cabeçalho `Idempotency-Key`; repetição com a mesma chave devolve o mesmo resultado.
B. Só os comandos de bot (iniciar, retomar, pausar, encerrar) e o kill switch.
C. Nenhum na API; a deduplicação fica só no ledger.
X. Other (please specify)

[Answer]: A

## Q8. [Arquitetura] Como o painel recebe atualizações sem recarregar?

O painel precisa refletir uma operação em até 60 segundos (FR12.2, AC5.4.1).

A. Consulta periódica (polling) da API a cada 15 segundos, com "Atualizado às HH:MM".
B. Server-Sent Events: o backend envia as mudanças por um canal aberto de uma via.
C. WebSocket com canal nos dois sentidos.
X. Other (please specify)

[Answer]: B

## Q9. [Arquitetura] Que garantia tem a entrega dos eventos de auditoria?

Seguimento da Units Generation (Q7, opção A: eventos em processo, sem outbox). A revisão de arquitetura apontou que a regra "exatamente um evento por ação sensível" (FR2.3, DP-2) depende de uma garantia de entrega ainda não definida: um evento publicado depois da gravação pode se perder se o processo cair entre os dois passos.

A. A gravação do evento de auditoria entra na mesma transação da ação, por chamada síncrona à interface do AuditTrail; os eventos em processo continuam só para o painel.
B. Manter o evento em processo publicado depois da gravação e aceitar a perda rara em queda, detectada por uma conferência periódica.
C. Tabela de saída (outbox) gravada na mesma transação e entregue ao AuditTrail depois.
X. Other (please specify)

[Answer]: B

## Q10. [Arquitetura] Como o esqueleto grava a auditoria do login?

O esqueleto (U1) grava os eventos de login antes de a Unit de auditoria (U2) existir. A revisão pediu que a troca entre a gravação mínima e a completa não mude quem chama.

A. O esqueleto já usa a interface final do AuditTrail, com uma implementação mínima; a U2 completa a implementação sem mudar a interface nem os chamadores.
B. O esqueleto grava direto numa tabela própria, e a U2 refatora os chamadores para a interface final.
X. Other (please specify)

[Answer]: A

## Q11. [Segurança] Como a sessão trafega entre navegador e API?

A sessão de login nunca autoriza trading e nenhum segredo pode ir para código entregue ao navegador.

A. Cookie de sessão `HttpOnly`, `Secure` e `SameSite=Strict` emitido pela API; o navegador fala com a API no mesmo endereço do app (proxy do Next.js); mutações levam um cabeçalho anti-CSRF.
B. O servidor do Next.js guarda a sessão e chama a API de servidor para servidor (BFF); o navegador nunca fala direto com a API.
C. Token de acesso guardado só na memória do navegador e enviado no cabeçalho `Authorization`.
X. Other (please specify)

[Answer]: C

## Q12. [Plataforma] O que o adaptador de mercado faz quando a fonte não responde?

Cada fonte fica atrás de um adaptador (FR4.1), e a falha de uma fonte degrada só o que depende dela (FR4.6). Os valores exatos são revistos no NFR Design.

A. Tempo limite curto (por exemplo, 5 s) e até 2 novas tentativas com espera crescente e variação aleatória; depois disso, a fonte fica marcada como com falha até a próxima coleta.
B. Sem nova tentativa: falhou, marca a fonte com falha e espera a próxima coleta.
C. Como A, mais um disjuntor por fonte que para de chamá-la por um tempo após falhas seguidas.
X. Other (please specify)

[Answer]: A

## Q13. [Segurança] O que acontece com a sessão ao recarregar a página?

Seguimento de Q11 (token só na memória). Um token guardado só na memória some quando a página é recarregada ou aberta em outra aba. Hoje, o critério AC1.3.1 diz que, depois de a sessão expirar e a pessoa entrar de novo, ela volta à tela onde estava.

A. Aceitar: recarregar a página ou abrir outra aba pede nova assinatura da carteira; depois de assinar, a pessoa volta à tela onde estava.
B. Híbrido: token de acesso curto na memória e token de renovação em cookie `HttpOnly`, `Secure` e `SameSite=Strict`, usado só para obter um novo token de acesso ao recarregar.
C. Trocar Q11 pelo cookie de sessão `HttpOnly` com proxy do Next.js (opção A de Q11).
X. Other (please specify)

[Answer]: B

## Q14. [Arquitetura] Como o canal de atualizações ao vivo se autentica?

Seguimento de Q8 (Server-Sent Events) e Q11 (token no cabeçalho `Authorization`). O `EventSource` nativo do navegador não envia cabeçalhos próprios, então não consegue mandar o token.

A. Abrir o canal com `fetch` em modo de leitura contínua, enviando o cabeçalho `Authorization`, em vez do `EventSource` nativo.
B. Pedir antes um ingresso de uso único e vida curta por uma chamada autenticada e passá-lo na URL do canal.
C. Trocar Q8 pela consulta periódica a cada 15 segundos (opção A de Q8).
X. Other (please specify)

[Answer]: A

## Q15. [Arquitetura] Auditoria com perda rara: o que muda no critério?

Seguimento de Q9 (manter o evento em processo e conferir). O requisito FR2.3 e a definição de pronto DP-2 dizem que cada ação sensível tem "exatamente um evento de auditoria". Com a opção B, uma queda do processo entre gravar a ação e publicar o evento deixa a ação sem evento até a conferência encontrar a falta.

A. Manter Q9: a conferência periódica compara as ações gravadas (convites, transições de bot, recusas de ordem, operações, kill switch) com os eventos e grava o evento que faltar com o ator e o horário originais e a marca "recuperado"; o critério passa a valer depois da conferência.
B. Manter Q9 e só alertar o administrador quando a conferência achar uma falta, sem gravar o evento depois.
C. Trocar Q9 pela gravação na mesma transação (opção A de Q9).
X. Other (please specify)

[Answer]: B

## Q16. [Arquitetura] Quem confere o estado do bot na hora de gravar a operação?

Revisão dos pontos críticos (pedida no Delivery Planning, Q7). Hoje a regra de C15 faz a execução PAPER (U7) ler o estado do bot por uma interface da U5, mas a U5 já depende da U7: isso cria um ciclo. O critério AC4.8.2 exige que a pausa seja conferida na mesma transação que grava a operação.

A. A U5 abre a transação do ciclo, trava a linha do bot e confere que ele está ACTIVE sem motivo de pausa; dentro dessa mesma transação chama a execução da U7, que não lê nada da U5.
B. A U7 define uma porta própria (por exemplo, `BotStateProbe`) que a U5 implementa; a U7 consulta essa porta dentro da sua transação, e a dependência de código continua só da U5 para a U7.
X. Other (please specify)

[Answer]: A

## Q17. [Arquitetura] Quem serve a lista de ordens recusadas?

As recusas pertencem à barreira (U6), mas o contrato as listava por uma rota da U7, e a U6 já depende da U7: isso cria um segundo ciclo (antiga OQ-5).

A. A U6 expõe a própria rota de leitura `GET /api/v1/bots/{botId}/rejections`.
B. A tela do bot lê as recusas pelo painel ou pela U5, que já conhece as recusas do ciclo.
X. Other (please specify)

[Answer]: A

## Q18. [Arquitetura] Como os eventos de auditoria do worker chegam à trilha?

O ciclo dos bots roda no processo worker, mas a entrega por evento em processo (Q9) só funciona dentro do mesmo processo, e a trilha (U2) estava só no processo de API. Esta escolha também decide o que acontece com a perda rara de eventos aceita em Q9 e Q15.

A. Tabela de saída (outbox) gravada na mesma transação da ação, em qualquer processo; o AuditTrail lê essa tabela e grava cada evento uma vez. Elimina a perda em queda e substitui Q9 e Q15.
B. O módulo AuditTrail roda também no worker (mesmo código), e cada processo entrega seus eventos depois do commit; mantém Q9 e Q15 (perda rara só alertada).
C. O worker avisa o processo de API pelo `LISTEN/NOTIFY` do PostgreSQL, e o AuditTrail da API grava o evento; mantém Q9 e Q15.
X. Other (please specify)

[Answer]: A

## Q19. [Arquitetura] Quem hospeda o canal ao vivo do painel e como ele vê o que o worker fez?

A U9 é uma Unit de interface (`ui`), mas o canal é código de backend; e os eventos do worker não chegam ao processo de API (antiga OQ-2).

A. A U5 hospeda o canal no processo de API e descobre as mudanças lendo, a cada poucos segundos, as marcas de "última alteração" dos bots, operações e fontes pelas interfaces públicas; a U9 só consome.
B. Como A, mas avisado pelo `LISTEN/NOTIFY` do PostgreSQL em vez da consulta periódica.
C. A U9 passa a ser `service` e hospeda o canal (exige voltar à Units Generation para mudar o tipo da U9).
X. Other (please specify)

[Answer]: A

## Q20. [Arquitetura] Corrijo também os demais pontos da revisão que não pedem decisão nova?

São eles: `tenantId` opcional nos eventos de antes do login, com regra explícita (R-04); `Idempotency-Key` fora da renovação de sessão e com escopo pela carteira antes do login (R-05); tipos completos de `factors`, `byToken`, `divergences` e das páginas, e referências entre blocos explicadas (R-07); percentual definido sem ambiguidade (R-08); papel `ADMIN` só pela entrada administrativa e regra de `mode: REAL` explicada (R-09).

A. Sim, corrigir todos esses agora.
B. Não, corrigir só os pontos críticos (Q16 a Q19).
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- API REST privada, usada só pelos dois apps do monorepo; nenhum consumidor externo no beta (Q1).
- Código primeiro: o backend gera o OpenAPI; a CI compara com o arquivo versionado, e os tipos do frontend são gerados dele (Q2).
- Duas especificações: API do convidado em `/api/v1/...` e API administrativa em `/api/v1/admin/...`, cada app gerando tipos só da sua (Q3).
- Mudança aditiva é livre; a que quebra vai no mesmo commit, com os dois apps regenerados e a CI verde; `/api/v1` só muda quando houver consumidor externo (Q4).
- `unknown` como objeto com `status` (`KNOWN` com `value`, `source`, `observedAt` e `stale`; `UNKNOWN` com `reason` e a fonte consultada, se houver) (Q5).
- Dinheiro e quantidades como texto decimal com escala fixa por tipo de valor, definida no contrato (Q6).
- Toda requisição que muda estado exige `Idempotency-Key`; repetir a chave devolve o mesmo resultado (Q7).
- Painel ao vivo por Server-Sent Events, aberto com `fetch` em leitura contínua para enviar o cabeçalho `Authorization` (Q8, Q14).
- Sessão: token de acesso curto na memória, enviado no cabeçalho `Authorization`, e token de renovação em cookie `HttpOnly`, `Secure` e `SameSite=Strict`, usado só para renovar ao recarregar (Q11, Q13).
- Auditoria por tabela de saída (outbox) gravada na mesma transação da ação, no processo de API ou no worker; o AuditTrail lê a tabela e grava cada evento uma vez. Isso substitui Q9 e Q15: nenhuma perda em queda, e "exatamente um evento" (FR2.3, DP-2) volta a valer sem ressalva (Q18).
- O esqueleto (U1) já usa a interface final do AuditTrail com implementação mínima; a U2 completa sem mudar os chamadores (Q10).
- Adaptadores de mercado: tempo limite curto (por exemplo, 5 s) e até 2 novas tentativas com espera crescente e variação aleatória; depois, a fonte fica com falha até a próxima coleta (Q12).
- A U5 abre a transação do ciclo, trava o bot, confere ACTIVE sem motivo de pausa e chama a execução da U7 dentro dela; a U7 não lê nada da U5 (Q16).
- A U6 expõe a própria rota de leitura das ordens recusadas (Q17).
- A U5 hospeda o canal ao vivo do painel no processo de API e descobre as mudanças lendo as marcas de "última alteração" a cada poucos segundos; a U9 só consome (Q19).
- Corrigir também os pontos da revisão sem decisão nova: `tenantId` opcional antes do login, `Idempotency-Key` fora da renovação e com escopo pela carteira antes do login, tipos completos, percentual sem ambiguidade e papel `ADMIN` só pela entrada administrativa (Q20).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
