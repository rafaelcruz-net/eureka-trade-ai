# Desenho de escalabilidade — U5 `u5-anonymization`

Conversa: português (Brasil). Implementa `construction/u5-anonymization/nfr-requirements/scalability-requirements.md` (NFR3.3). A concorrência (NFR5.5 e NFR5.6) está em `reliability-design.md`.

| Requisito | Desenho |
|---|---|
| NFR3.3 | A anonimização é síncrona, numa requisição do administrador, sem fila, job nem cache. Uma instância a mais da API não muda nada: a exclusão mútua vem das travas de linha do banco, não da memória do processo. O pool do agendador continua em 21, porque a U5 não cria job |
