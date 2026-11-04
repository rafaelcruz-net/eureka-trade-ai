# Perguntas do desenho não funcional — U3 Convites, contas e sessões

Contexto: os requisitos estão em [../nfr-requirements/](../nfr-requirements/) (revisão READY, com achados a resolver neste desenho, em [review-01.md](../nfr-requirements/reviews/review-01.md)). O design funcional está em [functional-spec.md](../functional-design/functional-spec.md). As perguntas abaixo cobrem só o que pede uma escolha sua.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Carga contra o limite de consultas (achado Medium):** o teste de carga roda com o perfil `loadtest`, que já existe na U1 e só sobe junto com o perfil `test`; ele eleva o limite de consultas do convite como já eleva o da entrada. O teste do próprio limite (10 por minuto por IP) roda separado, no perfil padrão.
- **Cache das respostas com o código (achado Medium):** a API já envia `Cache-Control: no-store` e `Referrer-Policy: no-referrer` em todas as respostas (filtro de cabeçalhos da U1); a U3 acrescenta testes desses cabeçalhos na emissão, na consulta do convite e na tela Conta. O app web envia os mesmos cabeçalhos na página do convite.
- **Saúde e termos (achado Low):** como a API não sobe sem os termos vigentes, a saúde da identidade depende só do banco; a condição dos termos sai da saúde.
- **Ordem entre limite e formato (achado Low):** primeiro o limite por IP, depois o formato do código; consulta mal formada também conta no limite.
- **Redirecionamento (achado Low):** o teste do retorno à origem inclui tabulação e quebra de linha no início e caminho interno que não é rota do convidado.
- **Chave de idempotência ausente (achado Low):** emissão e revogação sem chave respondem `400 VALIDATION_FAILED` e nada é gravado, como nas demais rotas com chave.
- **Aleatório só da produção (achado Low):** a produção usa só `SecureRandom`; a troca do gerador existe só nos testes.
- **IDs repetidos entre Units (achado Low):** nas referências cruzadas, o ID vem sempre com a Unit (por exemplo, "NFR6.5 da U1").

## Q1. [Segurança] Como o código vai no link do convite?

O link é aberto no app web (Next.js). Se o código estiver no caminho ou na query (`/convite/<código>` ou `?code=`), ele aparece nos logs de acesso do servidor web e de qualquer proxy à frente. Se estiver no fragmento (`/convite#<código>`), o navegador nunca o envia ao servidor web; a página lê o fragmento e chama a API com ele.

- A. No fragmento: `/convite#<código>`. O servidor web e os proxies nunca veem o código; a página o lê no navegador e o apaga da barra de endereço depois de ler
- B. No caminho: `/convite/<código>`, com a rota do app web registrando nos logs só o modelo `/convite/[código]`
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
