# Perguntas do Domain Design — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md`, `aidlc/spaces/default/codekb/eureka-trader-ai/architecture.md` e `component-inventory.md`, e `inception/practices-discovery/team-practices.md`.

Módulos existentes no backend (monólito modular, um módulo por pacote): `identity`, `audit`, `administration`, `feedback`, `bots`, `decision`, `riskcontrol`, `ledger`, `pnl`, `marketdata`, `scanner`, `riskassessment`, `ops`, `shared`. Regra do time: um módulo fala com outro só pela interface pública ou por evento, nunca lendo as tabelas do outro.

## Q1. Onde ficam as peças novas?

A) Assim:
- os termos v2, a política, o novo aceite e os expurgos de desafios e sessões ficam em `identity`, que já é dono do catálogo de termos, do aceite, das sessões e da carteira;
- a auditoria sem a carteira completa (HMAC) fica em `audit`;
- um módulo novo, `privacy`, é dono dos pedidos de titular, da verificação de identidade, do resumo de Acesso, do catálogo de retenção e da coordenação da anonimização. Cada módulo dono apaga ou troca os próprios dados, chamado por uma interface pública.

(recomendado)

B) Tudo dentro de `identity`
X) Other (please specify)

[Answer]: A

## Q2. Como a anonimização atravessa os módulos?

A) `privacy` abre uma transação e chama, nesta ordem, as interfaces públicas:
1. `bots`: parar e renomear os bots da pessoa;
2. `feedback`: apagar o feedback da pessoa;
3. `identity`: revogar as sessões, apagar a carteira e marcar a conta `ANONYMIZED`;
4. `audit`: publicar `ACCOUNT_ANONYMIZED`.

Se qualquer passo falha, tudo desfaz. A ordem trava primeiro os bots, para nenhum ciclo novo criar ordem durante a anonimização. `ledger` e `riskcontrol` não são chamados: nada muda neles (recomendado)

B) Cada módulo escuta um evento `AccountAnonymizationRequested` e se anonimiza sozinho, sem transação única
X) Other (please specify)

[Answer]: A

## Q3. De onde vem o resumo de Acesso?

A) `privacy` monta o resumo chamando interfaces públicas de leitura:
- `identity`: carteira mascarada, datas, versões aceitas;
- `bots`: quantidade de bots;
- `ledger`: quantidade de ordens PAPER;
- `feedback`: quantidade de feedbacks;
- e os próprios pedidos.

Nenhuma leitura direta de tabela de outro módulo (recomendado)

B) Uma consulta SQL única no `privacy` lendo as tabelas dos outros módulos
X) Other (please specify)

[Answer]: A

## Q4. Onde fica a trava do novo aceite?

A) Num filtro web do `identity`, que roda depois da autenticação nas rotas do convidado. Ele compara a versão aceita com a vigente e responde `403 TERMS_REACCEPTANCE_REQUIRED` fora da lista de rotas liberadas, mantida no próprio `identity`. Os comandos de bot também ficam bloqueados por esse filtro. O worker não passa por ele, então os ciclos de bots ativos continuam (recomendado)
B) Cada módulo checa o aceite nas próprias rotas
X) Other (please specify)

[Answer]: A

## Q5. E a estrutura das telas?

A) Assim:
- no app do convidado: `features/legal` (páginas `/termos` e `/privacidade`, `LegalDocument`), `SiteFooter` no shell, `features/account` ampliado (Meus dados, pedidos) e `features/terms-reacceptance` (T7);
- no app do administrador: `features/data-requests` (T6, T6a, T6b, T6c, T8), `LegalDocument` e `SiteFooter`.

Os tipos são gerados do contrato da API, como hoje (recomendado)

B) Um pacote de UI compartilhado entre os dois apps
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- `identity`: termos v2, política, novo aceite (filtro com rotas liberadas) e expurgos de desafios e sessões; `audit`: HMAC sem carteira completa; módulo novo `privacy`: pedidos, verificação, resumo de Acesso, catálogo de retenção e coordenação da anonimização.
- Anonimização: `privacy` abre uma transação e chama `bots` → `feedback` → `identity` → `audit` pelas interfaces públicas; falha desfaz tudo; `ledger` e `riskcontrol` não são tocados.
- Resumo de Acesso por interfaces públicas de leitura (`identity`, `bots`, `ledger`, `feedback`), sem ler tabelas de outros módulos.
- Trava do novo aceite num filtro web do `identity`; o worker não passa por ele.
- Telas: web com `features/legal`, `SiteFooter`, `features/account` ampliado e `features/terms-reacceptance`; admin com `features/data-requests`, `LegalDocument` e `SiteFooter`; tipos gerados do contrato.

- Looks correct
- Request changes

[Answer]: Looks correct
