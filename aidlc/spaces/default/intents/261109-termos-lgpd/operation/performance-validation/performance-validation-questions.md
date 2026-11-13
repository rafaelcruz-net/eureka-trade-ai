# Perguntas do Performance Validation

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10). Não há ambiente parecido com produção: o único é o Docker Compose local (`operation/environment-provisioning/environment-inventory.md`).

## Pergunta 1
Qual padrão de tráfego validar?

A) O perfil de referência do beta, igual ao das metas: 20 convidados simultâneos, uma ação a cada 2 s, por 5 minutos, com a rajada de 20 pessoas da trava de novo aceite e 1000 pedidos semeados para as rotas administrativas (recomendado: é o que os `performance-requirements.md` e `scalability-requirements.md` definem)
B) Um perfil maior, de crescimento
X) Other (please specify)

[Answer]: A

## Pergunta 2
Quais percentis?

A) p95, como as metas dos `performance-requirements.md`; o p50 e o p99 ficam como informação quando o cenário os relata (recomendado)
B) p99 como meta
X) Other (please specify)

[Answer]: A

## Pergunta 3
Onde validar?

A) Usar as cargas rodadas no Build and Test de 2026-11-11 (mesmo código de produção da release `v2026.11.12-1`, exceto o aquecimento da trava e a troca do log4j, cobertos pela 4ª rodada do `reacceptance`); a validação num ambiente parecido com produção fica pendente para quando houver AWS (recomendado: não há outro ambiente)
B) Rodar todas as cargas de novo agora
X) Other (please specify)

[Answer]: A

## Pergunta 4
Onde estão os gargalos prováveis?

A) Conexões do banco na subida (trava de novo aceite, já tratada com pool fixo e aquecimento), a anonimização com contenção de trava (`lock_timeout` de 2 s) e o expurgo da trilha em volume real (o `perf-audit.sh` da U2 ainda não rodou) (recomendado)
B) Outros
X) Other (please specify)

[Answer]: A
