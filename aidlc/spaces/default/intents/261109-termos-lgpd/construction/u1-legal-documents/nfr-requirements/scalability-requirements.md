# Requisitos de escala — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `construction/u1-legal-documents/functional-design/`, `inception/requirements-analysis/requirements.md` (NFR3), `inception/contract-design/contract-summary.md` (L1) e `nfr-requirements-questions.md` (Q1).

## Projeção

| ID | Requisito | Meta |
|---|---|---|
| NFR3.6 | Volume de versões | dezenas de versões ao longo do tempo; todas em memória |
| NFR3.7 | Concorrência | 20 acessos simultâneos no beta, com cache (NFR3.4) absorvendo picos |
| NFR3.8 | Instâncias | as mesmas instâncias da API; o catálogo é lido em cada instância na subida, sem estado compartilhado |

## Gatilhos

Nenhum ajuste de escala é previsto para a U1: o conteúdo é estático por implantação, e o cache reduz a carga.
