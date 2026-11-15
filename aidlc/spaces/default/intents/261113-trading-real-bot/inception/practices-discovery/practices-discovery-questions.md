# Perguntas de Practices Discovery — Trading REAL (re-run)

Conversa: português (Brasil). Base: rascunho em `team-practices.md`, `discovered-rules.md` e `evidence.md`, o `team.md` e o `project.md` atuais e o ADR-008 de `inception/domain-design/decisions.md`.

## Q1. [Way of Working / Walking Skeleton / Code Style] Atualizar três textos que já ficaram para trás?

A. Sim: tirar a lista de "Lacunas da CI" (o `ci.yml` já roda lint, tipos, Jest e `check:api` do admin), registrar que a verificação é `bash scripts/verify.sh`, já rodando na CI, e registrar que as fronteiras entre módulos já são verificadas por ArchUnit (9 classes)
B. Não, manter como estão
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Deployment / Testing Posture] Confirmar os acréscimos do rascunho?

A. Sim: rollback do REAL começa por desligar o flag ou acionar o kill switch, sempre mantendo o saque; o processo TxSigner segue o mesmo piso de 80% de cobertura; nenhum teste da CI usa devnet, mainnet ou KMS real (validador local, KMS simulado, respostas gravadas)
B. Não
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Testing Posture] O spec da carteira do bot, que sobe o validador Solana local, roda em toda mudança?

A. Sim, em toda mudança e no merge, como manda a regra de frequência: o `verify.sh` é a suíte completa; se a cota gratuita do GitHub Actions apertar, revemos depois com dados
B. Só quando a mudança tocar o REAL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Deployment] As condições para abrir aos convidados viram prática da equipe?

A. Sim: além da quarentena de 24 h, pelo menos 7 dias da carteira do fundador na mainnet sem divergência e o parecer de custódia decidido ficam no Deployment do `team.md`
B. Ficam só no plano de entrega desta intent
C. Not yet defined
X. Other (please specify)

[Answer]: A
