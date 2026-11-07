# Design de segurança — U9 Painel e navegação

Base: [security-requirements.md](../nfr-requirements/security-requirements.md) (NFR2.1, NFR4.1, NFR4.2, NFR5.1, NFR5.2, NFR6.1 a NFR6.6, NFR7.1), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md), [functional-spec.md](../functional-design/functional-spec.md), a resposta Q1 de [nfr-design-questions.md](nfr-design-questions.md) e o canal C7 de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

## Fronteiras de confiança

```
[Navegador: app do convidado] --HTTPS mesmo site, Authorization--> [API: rotas C1-C5 e canal C7]
        |                                                                  |
  token só em memória                                       tenant e pessoa só do token;
  cookie de renovação HttpOnly                              autorização em cada rota
```

Tudo que chega da API é tratado como dado: o painel nunca monta HTML a partir de texto recebido (React escapa por padrão; nenhuma `dangerouslySetInnerHTML` no painel e na casca).

## Controles

| Requisito | Design |
|---|---|
| NFR6.1 | O cliente do canal recebe o token do mesmo armazenamento em memória do cliente REST (`tokenStore`) e o põe só no cabeçalho `Authorization` de cada abertura. O teste do painel abre a tela, percorre `localStorage`, `sessionStorage` e IndexedDB e falha se alguma chave ou valor contém o token ou `Bearer` (as chaves de aceite da U3 continuam permitidas). |
| NFR6.2 | Regra de lint no `apps/web/eslint.config.mjs`: `no-restricted-globals` para `EventSource` e `no-restricted-syntax` para `new EventSource`, com mensagem que cita a regra do `project.md`. O lint já roda na CI e falha o merge. |
| NFR6.3 | Um único ponto de aviso do painel (`dashboardWarn(kind, reason)`) aceita só o tipo do evento e um motivo de uma lista fechada (`INVALID_ENVELOPE`, `UNKNOWN_EVENT`, `OVERSIZED_EVENT`, `NETWORK`); nunca recebe `data`, `id`, token nem valores. O teste do painel captura `console.warn` e `console.error` e procura token, `Bearer`, carteira e o padrão do `id` do evento. |
| NFR6.4 | Nenhuma mudança na política de conteúdo: o canal vai para a mesma origem da API já presente em `connect-src`. O teste de cabeçalho do `middleware.test.ts` continua valendo. |
| NFR6.5 | O validador de envelope confere o tipo de cada campo do envelope gerado do OpenAPI; campos a mais são ignorados, envelope inválido é descartado com `dashboardWarn`; nenhum texto recebido vira marcação. Teto de 1 MiB por evento (ver [performance-design.md](performance-design.md)). |
| NFR6.6 | O cliente do canal guarda `renewedOnce` por tentativa: `auth.expired`, ou `401` na abertura, leva a uma renovação por `POST /api/v1/auth/refresh`; um `401` na reabertura logo depois da renovação, ou a falha da renovação, fecha o canal sem novas tentativas e chama o fluxo de sessão expirada da U3. Testes: `auth.expired` com renovação, `401` na abertura com renovação, renovação que falha, `401` repetido depois da renovação. |
| NFR5.1 | As chamadas do painel são montadas só com `botId` do próprio resultado e período; o teste do cliente inspeciona todas as requisições da abertura, do "Pausar" e do canal e falha se aparecer `tenantId` ou `userId`. |
| NFR5.2 | `404` em "Pausar" ou na última ordem remove o bot da tela (BR2.2). Os testes negativos entre tenants das rotas usadas continuam nas Units donas (U5, U7). |
| NFR4.1 | `PauseButton` gera a chave na primeira tentativa e a guarda em `pendingPauses`; o botão fica desabilitado enquanto `SENDING`; "Tentar de novo" reusa a chave. |
| NFR4.2 | A formatação usa as funções decimais da U7, que trabalham sobre o texto; um teste com 18 casas confere o resultado exato, e a regra `no-restricted-syntax` acrescenta a proibição de `Number(`, `parseFloat(` e `+valor` nos arquivos de formatação do painel. |
| NFR7.1 | A casca mostra só `walletAddressMasked`; o endereço completo nunca chega ao app nesta Unit. |

## Teto de canais por pessoa (NFR2.1, Q1)

Implementado na geração de código da U9, dentro do canal que é da U5 (`DashboardMarkPoller` e `DashboardStreamController`), sem mudar o resto do canal:

- Ao abrir, o canal conta os canais abertos da mesma pessoa (tenant e usuário do token). Com 5 abertos, o 6º recebe `429 RATE_LIMITED` em `ProblemDetail`, com `Retry-After: 30` e `correlationId`, antes de abrir o fluxo. O limite fica na configuração do canal (`memetrade.bots.stream.max-channels-per-person`, padrão 5).
- Fechar um canal (completar, tempo esgotado, erro ou `auth.expired`) libera a vaga na hora.
- A recusa não gera evento de auditoria (não é ação sensível de domínio); vai para o log em nível `WARN` só com o identificador mascarado da pessoa e a contagem.
- O acréscimo (resposta `429` em `/api/v1/stream/dashboard`) é registrado em C7 no `contract-summary.md` pela geração de código, junto dos esquemas dos envelopes.
- Teste de integração: abre 6 canais da mesma pessoa, confere o `429` com `Retry-After` no 6º, fecha um e confere que o próximo abre; outra pessoa abre normalmente com a primeira no teto.
- No app, o `429` é uma queda: espera ao menos o `Retry-After` e segue a espera crescente de BR5.3.

## Ameaças e controles

| Ameaça (STRIDE) | Controle |
|---|---|
| Token lido por script ou extensão no armazenamento do navegador (I) | NFR6.1, NFR6.4 |
| Token, carteira ou dado no console (I) | NFR6.3 |
| Evento malformado ou enorme (T, D) | NFR6.5, teto de 1 MiB |
| Pessoa vê bot de outra (I, E) | NFR5.1, NFR5.2 e autorização das rotas |
| Cliques repetidos criando transições (T) | NFR4.1 |
| Muitas abas ou reconexões esgotando o servidor (D) | Teto de 5 canais (NFR2.1), espera crescente e `Retry-After` |
| Laço de reconexão com token vencido (D) | NFR6.6 |
