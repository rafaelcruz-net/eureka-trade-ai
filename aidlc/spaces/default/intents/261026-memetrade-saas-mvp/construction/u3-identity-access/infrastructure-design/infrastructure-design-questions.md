# Perguntas do desenho de infraestrutura — U3 Convites, contas e sessões

Contexto: o ambiente continua só local (Docker Compose no Windows; CI no GitHub Actions), como na U2 e na U4. O desenho não funcional está em [../nfr-design/](../nfr-design/) (revisão READY, com uma observação menor sobre o canal do código na emissão, em [review-02.md](../nfr-design/reviews/review-02.md)).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Nenhum serviço novo no Compose:** a U3 roda dentro do processo `api` e usa o PostgreSQL existente.
- **Migração `0007-identity-access.sql`:** só acrescenta (`invite`, `terms_acceptance`, índices únicos e a semeadura do tenant `Beta`), aplicada pelo `db-migrate`; nenhuma coluna existente é removida.
- **Limite da consulta do convite:** propriedade `memetrade.auth.invite-lookup-rate-limit-per-minute`, fixa em 10 no `application.yml` e elevada só no `application-loadtest.yml` (`INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE`, padrão 1000), como o limite da entrada. Não vira variável do Compose, para o uso diário nunca rodar com o limite alto.
- **Canal do código na emissão (observação da revisão):** a operação de emissão devolve um resultado tipado com o convite e o código; o corpo guardado na idempotência é serializado só do convite; a resposta HTTP é serializada de novo com o código quando `replayed` é falso. Um teste lê o registro de idempotência e confere que o código não está nele.
- **Cabeçalhos do app web:** o `middleware.ts` acrescenta `Cache-Control: no-store` em `/convite/*` e `/conta`.
- **Sem segredo novo:** o resumo do código usa SHA-256 sem chave (Q2 de NFR Requirements).

## Q1. [CI/CD] Como o smoke de ponta a ponta da U3 obtém um convite?

A tela administrativa de convites só chega na U8, e a entrada do administrador também. O smoke da U3 (Playwright) precisa de um convite pendente para abrir o link, aceitar os termos e criar a conta.

- A. Só no perfil `test`, um semeador (como o `SmokeAccountSeeder` da U1) cria um convite pendente cujo código vem de uma variável do ambiente de teste (`SMOKE_INVITE_CODE`, gerada pelo código de teste a cada execução); a carteira de teste também é gerada no código de teste
- B. O smoke chama a interface `IdentityAdministration` por uma rota de teste exposta só no perfil `test`, que emite um convite e devolve o código
- X. Other (please specify)

[Answer]: A

## Q2. [Conteúdo] De onde vem o texto da versão `v1` dos termos e do aviso de risco?

A API não sobe sem os termos (NFR10.3 da U3), e o texto final depende da revisão jurídica prevista no PRD antes dos convites reais.

- A. Eu escrevo um texto provisório para o beta, marcado no próprio texto como "versão provisória, pendente de revisão jurídica", sem promessa de lucro (catálogo de termos proibidos); você troca pelo texto revisado antes do primeiro convite real, publicando uma versão nova
- B. Você fornece o texto agora, antes da geração de código
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
