# Perguntas de Infrastructure Design — U2 `u2-real-market-data`

Conversa: português (Brasil). Base: `construction/u2-real-market-data/nfr-design/` (segurança, confiabilidade, observabilidade, componentes lógicos), `construction/u1-bot-wallet/infrastructure-design/infrastructure-specification.md` (rede da nuvem, NAT só para o worker, Secrets Manager, CloudWatch) e os dois pontos Major da segunda revisão do NFR Design (contador gravado depois de cada chamada; estados da fonte no coletor).

## Q1. [Local] O que muda no Compose?

A. Nada sobe a mais. O `docker-compose.yml` e o Compose de teste continuam com `MARKET_SOURCES=sim-a,sim-b`. A variável `COINGECKO_API_KEY` entra só com o nome no `.env.example`, e a fonte `coingecko` só é ligada quando alguém configura `MARKET_SOURCES=coingecko`. A migração da tabela `marketdata_provider_usage` entra no `db-migrate` que já existe.
B. Ligar a CoinGecko no Compose local
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Nuvem] Rede e segredo

A. A rede e o segredo na nuvem:
- O worker continua na subrede com NAT, como na U1. Sai para a internet só por HTTPS, com o grupo de segurança permitindo 443.
- Os domínios da CoinGecko são conferidos na aplicação, com a lista fixa.
- A chave fica no Secrets Manager, num segredo próprio e lido só pelo papel do worker. A API não recebe a chave.
B. Endpoint de VPC para a CoinGecko
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Correção da revisão] Contador e estados

A. Correção do contador e dos estados:
- **Contador:** o `CallBudget` grava cada tentativa numa transação própria e curta, logo depois da chamada, no upsert de `marketdata_provider_usage`. Assim os erros e as rodadas cortadas também contam.
- **Estados:** o `FetchResult` ganha um resumo da rodada, com preços pedidos, preços obtidos e se a rodada foi cortada. O coletor usa esse resumo para decidir entre OK e DEGRADED. A tolerância de duas rodadas fica no coletor, só para fontes que a pedem, e a `coingecko` pede. As fontes simuladas continuam como hoje.
- **Prazo interno:** o adaptador usa um prazo de 18 s pelo relógio injetável e devolve os preços que já tem antes dos 20 s do `SourceCaller`.
B. Deixar para o Code Generation sem desenho
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Monitoramento e CI] Onde ficam os alertas e testes

A. Monitoramento e CI:
- **Alertas:** os 6 alertas da U2 entram em `infra/observability/` (validados com `promtool`) e no módulo `observability` do Terraform, roteados pelo SNS da U1.
- **CI:** não muda. Os testes da U2 rodam no job do backend que já existe, e o WireMock é só de teste.
- **Compressão:** a recusa de resposta comprimida é testada contra a API real no primeiro dia de produção. Se a CDN ignorar o `Accept-Encoding`, o gzip passa a ser aceito, com o contador de 2 MB aplicado sobre o fluxo já descomprimido.
B. Pipeline separado
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Compose sem mudança de serviços; COINGECKO_API_KEY só no .env.example; migração no db-migrate [Q1].
- Worker sai por NAT em 443; chave no Secrets Manager só para o worker [Q2].
- Contador gravado depois de cada chamada; resumo da rodada no FetchResult; tolerância de duas rodadas no coletor; prazo interno de 18 s [Q3].
- Alertas em infra/observability e Terraform; CI sem mudança; compressão testada no primeiro dia [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
