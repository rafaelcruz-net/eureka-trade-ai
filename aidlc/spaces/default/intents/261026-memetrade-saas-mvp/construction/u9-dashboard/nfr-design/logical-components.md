# Componentes lógicos — U9 Painel e navegação

Inventário dos componentes onde os padrões de [performance-design.md](performance-design.md) e [security-design.md](security-design.md) se aplicam, a partir de [functional-spec.md](../functional-design/functional-spec.md), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md) e do canal C7 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). A U9 não cria serviço, processo nem banco: tudo roda no app do convidado, exceto o teto de canais, que fica no canal da U5 no processo de API.

## Inventário

| Componente | Onde roda | Responsabilidade | Padrões aplicados |
|---|---|---|---|
| `AppShell` (casca) | Navegador | Cabeçalho, menu, carteira abreviada, região de avisos | Leitura única de `GET /account` por sessão; só `walletAddressMasked` |
| `DashboardScreen` e `DashboardProvider` | Navegador | Estado do painel num redutor único | Grupos independentes; fila de eventos antes da resposta REST; "uma em andamento, uma pendente" |
| Limitador de concorrência | Navegador | Buscas de última ordem | No máximo 3 ao mesmo tempo |
| `DashboardStreamClient` | Navegador | Abrir, ler e reabrir o canal | `fetch` com `Authorization` e `X-Correlation-Id`; leitor próprio com teto de 1 MiB; espera crescente; `Retry-After`; uma renovação por tentativa |
| Validador de envelope | Navegador | Conferir cada evento | Tipos gerados do OpenAPI; descarte com `dashboardWarn` |
| `dashboardWarn` | Navegador | Avisos no console | Lista fechada de motivos; nunca dados |
| Canal C7 com teto (`DashboardStreamController`, `DashboardMarkPoller`) | Processo de API (módulo da U5) | Recusar o 6º canal da mesma pessoa | Contagem por pessoa; `429` com `Retry-After`; limite configurável |

## Domínios de falha e alcance

| Falha | Alcance | Comportamento |
|---|---|---|
| Uma rota de quadro falha | Só aquele quadro, só naquela aba | Erro com "Tentar de novo" no quadro (BR1.2) |
| A busca de última ordem de um bot falha | Só a linha "Última operação" | A linha mostra que não carregou; o resto do quadro segue |
| O canal cai ou é recusado com `429` | Atualização ao vivo daquela aba | Aviso de valores desatualizados; espera crescente; os valores já mostrados ficam |
| A renovação do token falha | A sessão da pessoa | Fluxo de sessão expirada da U3 |
| O canal do servidor fica lento | Todas as abas abertas | O app mostra o aviso após 60 s sem evento; nada no app depende de uma resposta do canal para mostrar os quadros |

## Recursos compartilhados

- O armazenamento do token em memória (`tokenStore`) e o cliente REST são compartilhados com todas as telas; a U9 só lê o token e não cria outra cópia.
- O canal C7 é compartilhado por todas as pessoas no processo de API; o teto de 5 canais por pessoa limita o que uma conta consome desse recurso.
- As funções decimais da U7 são compartilhadas com a tela S6.

## Para o Infrastructure Design

Nada de infraestrutura nova: nenhum serviço, contêiner, porta ou variável de ambiente nova, além da propriedade `memetrade.bots.stream.max-channels-per-person` (padrão 5) no processo de API.
