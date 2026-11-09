# Registro de restrições — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/market-research/build-vs-buy.md`, `competitive-analysis.md`, `market-trends.md` e `feasibility-questions.md`.

| ID | Tipo | Restrição | Origem |
|---|---|---|---|
| TL-C01 | Regulatória | Cumprir a LGPD: informar o titular (art. 9), atender os direitos (art. 18) e responder o pedido de acesso completo em até 15 dias (art. 19, II) | Q3; market-trends.md |
| TL-C02 | Técnica | O ledger é append-only: nenhum lançamento é editado ou apagado; a exclusão é por anonimização | Q2; regra do projeto |
| TL-C03 | Técnica | Nenhum script de terceiro nem rastreamento nas páginas | build-vs-buy.md |
| TL-C04 | Técnica | Textos versionados no repositório, servidos pelo catálogo da U3; versão nova exige novo aceite | Q1 |
| TL-C05 | Regulatória | O local de armazenamento e a eventual transferência internacional constam da política; a versão do beta só é publicada com esses campos preenchidos | Q3 |
| TL-C06 | Organizacional | Desenvolvedor solo, stack atual, sem orçamento extra; antes da abertura do beta, sem data fixa | Q4 |
| TL-C07 | Técnica | O e-mail do encarregado é configurado na implantação, nunca fixo no código | Q5 |
| TL-C08 | Conteúdo | Os textos não prometem lucro nem garantia de detecção de fraude e dizem que tudo é simulado (PAPER) | regra do projeto; competitive-analysis.md |
| TL-C09 | Técnica | O tenant vem sempre do contexto de autenticação; um pedido de titular só lê e altera dados da própria pessoa | regra do projeto |
| TL-C10 | Infraestrutura | Sem conta de nuvem: tudo roda no ambiente local até a hospedagem existir | Q6 |
