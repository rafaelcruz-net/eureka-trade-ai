# Perguntas do Functional Design — U1 `u1-legal-documents`

Conversa: português (Brasil). Base:
- `inception/units-generation/unit-of-work.md` (U1);
- histórias US1.1, US1.2, US1.3, US2.1, US2.2, US2.4, US2.5 e US2.6;
- `inception/contract-design/contract-summary.md` (L1, J5);
- o código atual do catálogo (`apps/api/src/main/resources/terms/v1/`, `TermsCatalog`).

Hoje cada versão é uma pasta com `version.properties` (`version`, `current`), `terms.txt` e `risk-notice.txt`, lida uma vez na subida. A API não sobe sem exatamente uma versão `current=true`. A v1 é marcada como provisória.

## Q1. Como a versão vigente é escolhida com a regra da revisão jurídica?

A) Mantém a marca explícita `current=true` e acrescenta `legalReviewedAt` e `legalReviewedBy` ao `version.properties`:
- se a versão marcada não tem os dois campos, a API usa a versão anterior mais nova, registra um aviso no log e sobe normalmente;
- a v1 atual recebe `legacy=true`, que a dispensa dos campos de revisão, para nunca faltar uma versão vigente;
- se nenhuma versão é ativável, a API não sobe, como hoje.

(recomendado)

B) Escolher automaticamente a versão mais nova com revisão, sem a marca `current`
X) Other (please specify)

[Answer]: A

## Q2. Em que formato ficam os textos?

A) Texto simples em UTF-8, nunca HTML, um arquivo por documento na pasta da versão: `terms.txt`, `privacy.txt`, `risk-notice.txt`. Linhas que começam com `## ` abrem uma seção, e uma linha em branco separa parágrafos. Os resumos ficam em `terms-summary.txt` e `privacy-summary.txt`, um tópico por linha, de 5 a 7 linhas. A tabela de retenção inicial fica em `retention.tsv` (dado, finalidade, prazo, ação no fim) até a U2 trazer o catálogo de retenção (recomendado)
B) Markdown, convertido para HTML no app
X) Other (please specify)

[Answer]: A

## Q3. Como tratar os campos de implantação ausentes?

A) Os três campos (local de armazenamento, transferência internacional, e-mail do encarregado) vêm de variáveis de ambiente (`MEMETRADE_LEGAL_STORAGE_LOCATION`, `MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER`, `MEMETRADE_LEGAL_DPO_EMAIL`):
- faltando algum, a página mostra "a definir" e a resposta traz `publishable: false`, o que basta no ambiente local e nos testes;
- com a chave `memetrade.legal.require-publishable=true`, ligada só em produção, uma versão não publicável não é ativada e vale a anterior, com aviso no log. Isso cumpre a regra de "não publicar sem os campos".

(recomendado)

B) A API não sobe sem os três campos, em qualquer ambiente
X) Other (please specify)

[Answer]: A

## Q4. O que mostra o histórico de versões (Could)?

A) Todas as versões conhecidas pelo catálogo, mais nova primeiro, com data de vigência e link para `/termos?versao=vN` e `/privacidade?versao=vN`, só para leitura. Uma versão dispensada da revisão (legacy) aparece marcada como "versão provisória". Versões futuras ainda não vigentes não aparecem (recomendado)
B) Mostrar só a vigente e a anterior
X) Other (please specify)

[Answer]: A

## Q5. Como as páginas tratam erro e versão antiga?

A) Três casos:
- erro ou indisponibilidade da API: aviso de erro com "tentar de novo", nunca página vazia;
- `?versao=` inexistente: "Versão não encontrada", com link para a vigente;
- versão antiga aberta pelo histórico: faixa no topo "Esta não é a versão vigente".

As páginas não guardam nada no navegador (recomendado)

B) Redirecionar sempre para a vigente
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Versão vigente: marca `current=true` mais `legalReviewedAt` e `legalReviewedBy`; sem revisão, vale a anterior mais nova com aviso no log; v1 com `legacy=true` dispensada da revisão; sem nenhuma ativável, a API não sobe.
- Formato: texto simples UTF-8 (`terms.txt`, `privacy.txt`, `risk-notice.txt`), seções com `## `, parágrafos por linha em branco; resumos em `terms-summary.txt` e `privacy-summary.txt` (5 a 7 linhas); `retention.tsv` até a U2.
- Campos de implantação por variáveis de ambiente; faltando, "a definir" e `publishable: false`; com `memetrade.legal.require-publishable=true` (produção), versão não publicável não é ativada.
- Histórico: todas as versões vigentes ou passadas, mais nova primeiro, com data e link de leitura; legacy marcada "versão provisória"; futuras não aparecem.
- Páginas: erro com tentar de novo; versão inexistente com link para a vigente; faixa "Esta não é a versão vigente"; nada guardado no navegador.

- Looks correct
- Request changes

[Answer]: Looks correct
