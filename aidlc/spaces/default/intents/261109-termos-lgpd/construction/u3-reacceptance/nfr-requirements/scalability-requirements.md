# Requisitos de escalabilidade — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes: `construction/u3-reacceptance/functional-design/` (BR3.1, BR3.5) e `inception/requirements-analysis/requirements.md` (NFR3).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR3.4 | A trava não guarda estado em memória: lê o banco a cada requisição, então todas as instâncias decidem igual, inclusive logo depois de um aceite | teste de integração: aceite seguido de requisição com o mesmo token |
| NFR3.5 | Dois aceites concorrentes da mesma pessoa e versão resultam em um único registro; o segundo devolve o mesmo resultado | teste de integração com duas chamadas concorrentes |
| NFR3.6 | Volume do beta: até 20 convidados, cada um com um aceite por versão; a tabela cresce uma linha por pessoa e versão | projeção registrada; passa quando o NFR3.1 passa com 20 usuários |
