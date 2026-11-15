# Alocação — Trading REAL

Conversa: português (Brasil). Fontes: `bolt-plan.md`, `inception/units-generation/unit-of-work.md`, `inception/units-generation/unit-of-work-dependency.md` e `delivery-planning-questions.md` (Q4, Q9).

Um **Bolt** é uma passada de construção sobre uma Unit que termina em algo que roda. Um **mob** é o grupo que executa um Bolt junto; aqui ele é formado por um agente de IA e pelo fundador.

## Alocação

Não houve Team Formation neste escopo (`classic`). Todos os Bolts são executados pelo aidlc-developer-agent (IA), com o fundador aprovando cada ponto de controle e decidindo nos gates [Q9]. Os Bolts andam um por vez, em série [Q4].

| Bolt | Unit | Executa | Aprova | Apoio |
|---|---|---|---|---|
| B1 | U1 `u1-bot-wallet` | aidlc-developer-agent | fundador | arquiteto e DevSecOps nos estágios de design (política do TxSigner) |
| B2 | U2 `u2-real-market-data` | aidlc-developer-agent | fundador | — |
| B3 | U3 `u3-real-risk` | aidlc-developer-agent | fundador | qualidade (testes antes do código) |
| B4 | U4 `u4-real-execution` | aidlc-developer-agent | fundador | qualidade (estados e idempotência) |
| B5 | U5 `u5-real-operations` | aidlc-developer-agent | fundador | operações (alertas e reconciliação) |
| B6 | U6 `u6-real-modes-ui` | aidlc-developer-agent | fundador | design (pontos de tela em aberto) |
| B7 | U7 `u7-exits-and-fiscal` | aidlc-developer-agent | fundador | — |

## Papéis que só o fundador cumpre

- Aprovar os pontos de controle, a mudança de regras do ADR-008 e o comando de verificação.
- Alterar `scripts/verify.sh` e `.github/workflows/*.yml` (os specs de ponta a ponta novos de cada Bolt entram no script pelas mãos dele).
- Abrir a conta AWS, contratar a CoinGecko e os provedores de RPC, decidir o parecer de custódia.
- Revisor único dos gates REAL nesta fase, sem G3.
