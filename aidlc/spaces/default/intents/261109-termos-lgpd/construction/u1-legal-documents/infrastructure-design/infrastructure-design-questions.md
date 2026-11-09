# Perguntas do Infrastructure Design — U1 `u1-legal-documents`

Conversa: português (Brasil). Base: `construction/u1-legal-documents/nfr-design/` e o que existe no repositório. O `docker-compose.yml` usa a rede padrão do Compose, sem faixa fixa. A CI tem `ci.yml`, `load-test.yml` (carga manual, com os cenários das Units) e `security.yml`. O `verify.sh` e o `ci.yml` são alterados só pelo responsável do projeto.

## Q1. Como levar a configuração da U1 ao Compose?

A) Três mudanças:
- o serviço `api` ganha as variáveis dos campos de implantação (vazias por padrão, o que mostra "a definir"), a exigência de publicação desligada e a lista de rede interna;
- o `.env.example` ganha os nomes, sem valores;
- a rede do Compose ganha uma faixa fixa (por exemplo `172.28.0.0/16`), para a lista de rede interna ter um valor conhecido.

O catálogo v2 vai dentro da imagem da API, como a v1 (recomendado)

B) Descobrir a faixa da rede na subida
X) Other (please specify)

[Answer]: A

## Q2. Como entra na verificação e na carga?

A) Duas mudanças:
- você acrescenta o spec `e2e/legal-documents` ao `verify.sh`, como fez com os smokes das outras Units;
- o cenário `legal` entra no workflow manual `load-test.yml` como mais uma opção.

Nenhum job novo na CI (recomendado)

B) Um job novo na CI só para a U1
X) Other (please specify)

[Answer]: A

## Q3. E na nuvem, quando existir?

A) Só desenho:
- os três campos e a exigência de publicação ligada vêm do Parameter Store;
- a lista de rede interna recebe as faixas das sub-redes privadas onde rodam os apps web e admin, nunca a do balanceador;
- o cache de 5 minutos continua.

Nada é criado agora (recomendado)

B) Não registrar nada da nuvem
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Compose: `api` com as variáveis dos campos de implantação (vazias), exigência de publicação desligada e lista de rede interna; `.env.example` com os nomes, sem valores; rede do Compose com faixa fixa (`172.28.0.0/16`); catálogo v2 dentro da imagem.
- Verificação: spec `e2e/legal-documents` acrescentado ao `verify.sh` pelo responsável do projeto; cenário `legal` como opção no `load-test.yml`; nenhum job novo.
- Nuvem (só desenho): campos e exigência de publicação ligada pelo Parameter Store; lista de rede interna com as sub-redes privadas dos apps, nunca a do balanceador; cache de 5 min mantido.

- Looks correct
- Request changes

[Answer]: Looks correct
