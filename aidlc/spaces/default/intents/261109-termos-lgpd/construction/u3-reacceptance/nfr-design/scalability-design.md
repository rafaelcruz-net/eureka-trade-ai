# Desenho de escalabilidade — U3 `u3-reacceptance`

Conversa: português (Brasil). Implementa `construction/u3-reacceptance/nfr-requirements/scalability-requirements.md` (NFR3.4 a NFR3.6).

| Requisito | Desenho |
|---|---|
| NFR3.4 | Nenhum cache: a trava consulta o banco a cada requisição, então todas as instâncias veem o aceite logo depois de gravado |
| NFR3.5 | Dois aceites concorrentes são resolvidos pelo `ON CONFLICT DO NOTHING` na restrição única. O segundo lê o aceite gravado e devolve o mesmo `TermsStatus`, e só a inserção efetiva publica o evento |
| NFR3.6 | Uma linha por pessoa e versão; com 20 convidados, o volume é irrelevante |
