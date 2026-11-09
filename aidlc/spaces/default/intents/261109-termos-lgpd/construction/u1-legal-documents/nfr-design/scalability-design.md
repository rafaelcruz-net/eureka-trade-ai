# Design de escala — U1 `u1-legal-documents`

Conversa: português (Brasil). Atende `construction/u1-legal-documents/nfr-requirements/scalability-requirements.md` (NFR3.6 a NFR3.8).

## Abordagem

- **Sem estado compartilhado:** cada instância da API lê o catálogo na própria subida (NFR3.8). Escalar é adicionar instâncias.
- **Memória:** dezenas de versões, até 200 KB por arquivo; poucos megabytes no pior caso (NFR3.6).
- **Picos:** absorvidos pelo cache HTTP de 5 minutos e pelo cache de dados do app (NFR3.7).
- **Limitador:** contadores em memória por instância, como o do convite. Com várias instâncias, o limite efetivo é por instância. Isso é aceitável no beta e fica registrado para a infraestrutura.

## Limites de capacidade

| Item | Limite |
|---|---|
| Tamanho de arquivo | 200 KB |
| Seções por versão | 100 |
| Acessos simultâneos de referência | 20 (cenário `legal`) |
