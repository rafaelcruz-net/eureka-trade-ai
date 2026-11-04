# Perguntas de requisitos não funcionais — U3 Convites, contas e sessões

Contexto: o design funcional da U3 está em [functional-spec.md](../functional-design/functional-spec.md) e [rules.md](../functional-design/rules.md). Os requisitos da inception que tocam a U3 são NFR3 (p95 ≤ 2 s nas telas com 20 usuários simultâneos), NFR5 (nenhum acesso entre tenants), NFR6 (nenhuma chave ou frase secreta coletada, nonce de uso único), NFR7 (endereços mascarados em logs e métricas, retenção mínima após o inventário), NFR9 (métrica de saúde e de erro por componente, correlação nos logs), NFR11 (acessibilidade básica), NFR12 (cobertura de 80%) e NFR13 (Windows e Linux com o mesmo resultado).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- Sessão, desafio, limite da entrada (10 por minuto por IP) e tempo de resposta da entrada sem convite seguem a U1 (NFR3.1 e NFR6.5 da U1).
- O limite de 10 consultas por minuto por IP na consulta do convite (BR2.2) usa contadores na memória do processo de API, como a entrada da U1.
- Capacidade: 20 convidados no beta, então no máximo algumas dezenas de convites; não há meta de escala própria da U3.
- Sem alertas automáticos enquanto o beta roda só localmente: métricas e logs, como na U2 e na U4.
- Termos e aviso de risco ficam num arquivo do repositório (Q6 do design funcional).

## Q1. [Desempenho] Qual o tempo máximo de resposta da verificação com convite?

A verificação com convite faz mais trabalho que a entrada normal: bloqueia o convite e cria pessoa, carteira, aceite, evento e sessão numa só transação.

- A. p95 ≤ 500 ms para a verificação com convite; consulta do convite e tela Conta com p95 ≤ 300 ms, como a U1
- B. p95 ≤ 300 ms para todas as rotas da U3, igual à U1
- X. Other (please specify)

[Answer]: A

## Q2. [Segurança] Como o resumo do código do convite é calculado?

O código tem 256 bits aleatórios, então adivinhá-lo a partir do resumo não é viável. Uma chave secreta protege também o caso de o banco vazar junto com uma lista de códigos.

- A. SHA-256 simples do código, sem chave secreta (menos uma variável de ambiente a gerir)
- B. HMAC-SHA-256 com uma chave secreta lida do ambiente (`.env` local), nunca no repositório; trocar a chave invalida os convites pendentes
- X. Other (please specify)

[Answer]: A

## Q3. [Privacidade] Por quanto tempo os convites ficam guardados?

Um convite usado aponta para a conta criada. Convites vencidos ou revogados não apontam para ninguém.

- A. Todos ficam guardados durante o beta; a regra de retenção sai do inventário de dados (SP-3), como os demais dados pessoais
- B. Vencidos e revogados são apagados 90 dias depois; usados ficam enquanto a conta existir
- X. Other (please specify)

[Answer]: A

## Q4. [Segurança] As rotas administrativas de convite têm limite de requisições?

Só existe um administrador, e as rotas exigem o token dele.

- A. Sim: 30 emissões ou revogações por minuto por administrador; acima disso, `429` com `Retry-After`
- B. Não: o filtro de administrador e a chave de idempotência bastam no beta
- X. Other (please specify)

[Answer]: B

## Q5. [Observabilidade] Quais métricas próprias a U3 publica?

- A. Contadores de convites emitidos, revogados e usados, de contas criadas e de recusas da verificação com convite por motivo (`reason`), sem código, endereço nem identificador de pessoa
- B. Só a métrica de saúde e a de erro exigidas pelo NFR9
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
