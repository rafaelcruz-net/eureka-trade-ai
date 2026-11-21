# Perguntas da Performance Validation — intent 261113-trading-real-bot

Conversa: português (Brasil). As respostas seguem a instrução do responsável "siga sem me perguntar": em cada pergunta foi escolhida a opção recomendada.

Fontes:
- `construction/u*/nfr-requirements/performance-requirements.md` e `availability` (NFR3.x e NFR4.x);
- `construction/u*/nfr-design/performance-design.md`;
- `construction/build-and-test/build-and-test-summary.md`, com as 19 metas de desempenho e disponibilidade que pertencem a esta etapa;
- `construction/build-and-test/performance-test-instructions.md`;
- `operation/observability-setup/` (SLOs e alarmes);
- `.github/workflows/load-test.yml`, `scripts/load-test.sh` e os drivers do perfil `loadtest`.

Situação: não existe ambiente parecido com produção, porque não há conta AWS. O `load-test.yml` já roda carga manual no GitHub Actions, com Docker Compose e o perfil `test,loadtest`, mas não tem nenhum cenário do REAL. O repositório é público, então os minutos do Actions não são cobrados.

## Pergunta 1 — Onde rodar a carga

Onde a carga do REAL roda?

A. Em duas camadas:
   - **Agora:** um cenário `real` no `load-test.yml`, no GitHub Actions, com Docker Compose, o validador Solana local, o `kms-sim` e a Jupiter simulada. Isso fecha as metas que não dependem da nuvem: latência e vazão dos caminhos do REAL e capacidade do piloto (21 carteiras). Fica registrado como "ambiente de CI, não produção".
   - **Depois do primeiro apply:** uma rodada na devnet AWS, com o mesmo cenário apontado para o ambiente. Ela fecha as metas que dependem do KMS real, do RDS e do balanceador.
   - As metas de disponibilidade de 30 dias (U1:NFR4.1 e U2:NFR4.1) só se medem com tráfego real e continuam pendentes, com o comando de medição pronto.

   (recomendado)
B. Esperar a devnet AWS e testar tudo lá
C. Rodar a carga nesta máquina
X. Other (please specify)

[Answer]: A

## Pergunta 2 — Padrão de tráfego

Que padrão de tráfego simular?

A. O do piloto, multiplicado por 2:
   - 21 carteiras do bot (o fundador mais 20 convidados);
   - 3 bots ativos por carteira, com um ciclo a cada 5 minutos;
   - 20 pessoas na tela, com uma ação a cada 2 s, como no cenário u1;
   - um pico de "vender tudo e sacar" em 5 carteiras ao mesmo tempo;
   - um burst de saques: 21 pedidos em 10 s.

   O estado estável roda 15 minutos. O pico e o burst rodam 5 minutos cada. (recomendado)
B. Só o estado estável do piloto, sem margem
X. Other (please specify)

[Answer]: A

## Pergunta 3 — Metas de latência

Quais metas de latência valem?

A. As metas dos requisitos de cada Unit, sem mudar nenhuma. Exemplos:
   - p95 da barreira de risco;
   - p95 do envio da decisão até a rede;
   - p95 da confirmação;
   - p95 do preenchimento da condicional;
   - p95 da exportação fiscal;
   - p95 das rotas do REAL (até 1 s);
   - p95 do ciclo do worker.

   O relatório mostra p50, p95 e p99 de cada uma. Uma meta não atingida volta para o NFR Design, conforme o B&T. (recomendado)
B. Afrouxar as metas para o ambiente de CI
X. Other (please specify)

[Answer]: A

## Pergunta 4 — Vazão e gargalos

Que vazão sustentar, e onde provavelmente estão os gargalos?

A. Vazão: a do Q2 sem erro, sem ordem duplicada e sem fila crescendo. Nenhuma carteira pode passar de 60 assinaturas por minuto. Os gargalos a observar:
   - o pool de conexões do worker (28/36);
   - a trava por pessoa da barreira;
   - o TxSigner único;
   - o limite por carteira (60 por minuto);
   - o RPC;
   - os trabalhos de confirmação de 2 s.

   O cenário mede cada um com as métricas do `/actuator/prometheus` e com os planos de consulta (EXPLAIN), como os cenários u5, u6 e u7 já fazem. (recomendado)
B. Medir só a latência das rotas
X. Other (please specify)

[Answer]: A

## Pergunta 5 — Quem altera o `load-test.yml`

Como o cenário `real` entra no `load-test.yml`, que é um arquivo do responsável?

A. O driver e o roteiro do cenário entram no código: no perfil `loadtest`, no `scripts/load-test.sh` e nos specs ou drivers de carga. Isso é escrito aqui e testado. A opção `real` no `load-test.yml` chega como texto pronto para o responsável aplicar. Depois disso, o assistente dispara o workflow (`gh workflow run load-test -f scenario=real`) e analisa o relatório. (recomendado)
B. O assistente edita o `load-test.yml` direto
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- A carga roda em duas camadas [Q1]:
  - **agora:** cenário `real` no `load-test.yml` do GitHub Actions, com Compose, validador local, `kms-sim` e Jupiter simulada. Fecha as metas de latência, vazão e capacidade do piloto, registradas como "ambiente de CI";
  - **depois do primeiro apply:** a mesma rodada na devnet AWS, para as metas do KMS real, do RDS e do balanceador.

  A disponibilidade de 30 dias continua pendente, com o comando de medição pronto.
- O tráfego é o do piloto multiplicado por 2 [Q2]:
  - 21 carteiras, com 3 bots a cada 5 min;
  - 20 pessoas na tela;
  - um pico de "vender tudo" em 5 carteiras;
  - um burst de 21 saques em 10 s.

  O estado estável roda 15 min, e o pico e o burst rodam 5 min cada.
- As metas de latência são as dos requisitos, sem mudar nenhuma. O relatório mostra p50, p95 e p99, e uma meta não atingida volta para o NFR Design [Q3].
- Vazão sem erro, sem duplicata e sem fila crescendo, com no máximo 60 assinaturas por minuto por carteira. Os gargalos medidos são o pool, a trava por pessoa, o TxSigner único, o limite por carteira, o RPC e a confirmação, com os planos de consulta [Q4].
- O driver e o roteiro entram no código e são testados aqui. A opção `real` do `load-test.yml` chega como texto pronto para o responsável. Depois, o assistente dispara o workflow e analisa o resultado [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
