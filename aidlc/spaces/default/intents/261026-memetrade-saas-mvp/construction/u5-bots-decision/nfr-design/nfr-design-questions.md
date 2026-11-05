# Perguntas do NFR Design — U5 Bots e decisão

Os requisitos estão em [../nfr-requirements/](../nfr-requirements/) e o design funcional em [../functional-design/](../functional-design/).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Despacho do ciclo.** Um único disparo por fatia (cron UTC em `slotStart + 60 s`) põe os bots ativos numa fila. Um executor de 4 linhas processa a fila. Cada ciclo abre a sua transação.
- **Ordem das travas, falha fechada e tempos de espera.** Seguem as BR2.11 e BR3.3 e o NFR10.7.
- **Pool do worker.** O worker tem 10 conexões (NFR2.4). A API mantém o pool atual.
- **Sem cache** de dados de mercado ou de estado do bot entre ciclos.

## Q1. [Desempenho] Como o canal ao vivo (C7) lê as marcas

Com até 20 canais abertos, cada um precisa saber a cada 2 s se algo mudou para a sua pessoa.

A. Um leitor só, a cada 2 s, busca numa consulta as marcas de todas as pessoas com canal aberto e avisa só os canais cuja marca mudou.
B. Cada canal faz a própria leitura a cada 2 s.
X. Other (please specify)

[Answer]: A

## Q2. [Confiabilidade] Quantos workers podem rodar ao mesmo tempo

A. Um worker só no beta. O disparo da fatia usa ShedLock, então um segundo worker que subir por engano não processa nada em dobro.
B. Vários workers dividem os bots da fatia com `SELECT ... FOR UPDATE SKIP LOCKED`.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
