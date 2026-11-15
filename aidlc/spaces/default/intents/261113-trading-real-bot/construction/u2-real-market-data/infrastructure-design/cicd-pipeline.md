# Pipeline — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-requirements/tech-stack-decisions.md`, `construction/u1-bot-wallet/infrastructure-design/cicd-pipeline.md` e `infrastructure-design-questions.md` (Q4).

## CI

- O job novo da U1 continua igual, e a CI não muda [Q4]:
  - Os testes da U2 rodam no job `backend` que já existe: Maven `verify` com piso de 80% e ArchUnit (regra de rede ajustada).
  - O WireMock é dependência só de teste.
  - O `promtool` valida as regras novas de `infra/observability/`, já incluídas na etapa da U1.
  - Um teste confere que os limites dos alarmes do Terraform são os mesmos das regras do `promtool`.
  - Um teste de subida confirma que a API sobe com `MARKET_SOURCES=coingecko` sem a chave.
- O `verify.sh` continua com as fontes simuladas, e nenhum teste chama a CoinGecko.

## Implantação

A ordem e o rollback estão em `infrastructure-specification.md`:
- **Ordem:** migração, segredo, worker com `coingecko`, API com `coingecko`.
- **Rollback:** primeiro o REAL desligado; depois a imagem e o `MARKET_SOURCES` juntos; a tabela fica; o REAL só volta a ser ligado com a `coingecko` de volta.

Na devnet, antes da produção, o fundador confere com a API real que a resposta vem sem compressão. Se a CDN ignorar o `Accept-Encoding`, o gzip passa a ser aceito, com o contador de 2 MB aplicado sobre o fluxo já descomprimido [Q4].
