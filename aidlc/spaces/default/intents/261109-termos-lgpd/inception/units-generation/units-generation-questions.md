# Perguntas da Geração de Units — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/domain-design/components.md` e `decisions.md`, `inception/requirements-analysis/requirements.md` e `inception/user-stories/stories.md`. A prática do time pede uma fatia fina primeiro: `/privacidade` servida pela API e mostrada sem login, com spec novo no `verify.sh`.

## Q1. Como dividir o trabalho em Units?

A) Por funcionalidade, em cinco Units, cada uma de ponta a ponta (API, banco, telas e testes):
- **U1** `u1-legal-documents`: catálogo v2, ativação com revisão jurídica, páginas `/termos` e `/privacidade`, rodapé e histórico. É a fatia fina (US1.1–US1.3, US2.1, US2.2, US2.4–US2.6).
- **U2** `u2-retention`: auditoria com HMAC, expurgos de desafios e sessões, catálogo de retenção e teste de conformidade (US1.4, US3.1, US3.2).
- **U3** `u3-reacceptance`: filtro de novo aceite, rota de aceite, tela T7 e convite com as duas caixas (US2.3, US4.1, US4.2).
- **U4** `u4-data-requests`: módulo `privacy` com pedidos, Conta, telas do administrador, verificação, resumo, vencimento e expurgo (US5.1–US5.9).
- **U5** `u5-anonymization`: anonimização coordenada e conta anonimizada (US6.1–US6.3).

(recomendado)

B) Por camada (uma Unit de backend, uma de telas)
X) Other (please specify)

[Answer]: A

## Q2. Que dependências e que ordem?

A) Assim:
- U1 primeiro (fatia fina);
- U2 depende de U1, porque o catálogo de retenção alimenta a tabela da política;
- U3 depende de U1, por causa da versão vigente;
- U4 depende de U1, porque o botão "Fazer um pedido" está na política;
- U5 depende de U4 (pedido de Exclusão e verificação) e de U2 (trilha sem a carteira).

U2, U3 e U4 não dependem entre si, mas o trabalho segue uma Unit por vez, como o time faz (recomendado)

B) Permitir Units em paralelo
X) Other (please specify)

[Answer]: A

## Q3. Como as Units se integram?

A) Pelas interfaces Java públicas entre módulos, na mesma transação quando for preciso (anonimização), e pela API REST, que os apps consomem com tipos gerados do contrato. Sem evento entre processos. A lista de rotas liberadas do filtro (U3) recebe o prefixo das rotas de pedidos (U4) por configuração (recomendado)
B) Por eventos
X) Other (please specify)

[Answer]: A

## Q4. Como implantar?

A) Tudo no mesmo monólito modular e nos dois apps atuais, numa implantação só. Migrações em expand-contract (recomendado)
B) Um serviço separado para `privacy`
X) Other (please specify)

[Answer]: A

## Q5. Que tipo dar a cada Unit?

A) As cinco como `service`: todas têm API, banco e telas, e precisam do conjunto completo de artefatos de design (recomendado)
B) Separar as telas em Units `ui`
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Cinco Units por funcionalidade, de ponta a ponta: U1 `u1-legal-documents` (fatia fina), U2 `u2-retention`, U3 `u3-reacceptance`, U4 `u4-data-requests`, U5 `u5-anonymization`.
- Dependências: U2, U3 e U4 dependem de U1; U5 depende de U4 e U2; uma Unit por vez.
- Integração: interfaces Java públicas (transação única na anonimização) e API REST com tipos gerados; prefixo das rotas de pedidos passado ao filtro por configuração; sem eventos entre processos.
- Implantação: monólito modular e os dois apps atuais, numa implantação; migrações expand-contract.
- Tipo: as cinco como `service`.

- Looks correct
- Request changes

[Answer]: Looks correct
