# Perguntas do desenho de infraestrutura — U4 Descoberta e avaliação

Contexto: o ambiente continua só local (Docker Compose no Windows; CI no GitHub Actions), como decidido na U2. O desenho não funcional está em [../nfr-design/](../nfr-design/) (revisão READY com achados a resolver aqui, em [review-01.md](../nfr-design/reviews/review-01.md)).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Achado R-02, variação de 1 hora:** a amostra é escolhida pelo `observed_at` (entre agora − 65 min e agora − 60 min, inclusive), como diz a BR1.10; empate: a de `observed_at` mais recente, depois o menor `source_id`. Testes de fronteira em 60:00, 65:00 e com "agora" alinhado a 5 minutos.
- **Achado R-03, saúde do esqueleto:** `marketSources` substitui o indicador `marketSource` da U1 e mantém no detalhe `lastUpdatedAt` (o mais recente entre as fontes) e o estado de cada fonte; a saúde geral fica `UP` na operação normal (`UNKNOWN` do recálculo ainda não executado não rebaixa o agregado). O `scripts/verify.sh` continua funcionando sem mudança.
- **Achado R-04, consulta da lista:** a etapa 1 exige alguma fonte não `FAILED` para o token entrar na foto.
- **Achado R-05, execução:** chamadas às fontes num executor próprio `market-source-calls` (2 linhas, uma por fonte, usado pelo `TimeLimiter`); recálculo num executor próprio de 1 linha `risk-recalc`, que repete enquanto houver gatilho pendente (dois gatilhos durante uma execução geram exatamente uma execução a mais).
- **Fontes simuladas no Compose:** 200 tokens por fonte no uso diário (com 50 em comum) e 2 000 no script de carga, por variável de ambiente.

## Q1. [Desempenho] Como as consultas da lista ganham conexões suficientes? (achado R-01, Major)

Hoje o pool principal tem 10 conexões. A coleta, o recálculo e a limpeza usam até 3, e a meta da lista (p95 ≤ 1 s) é medida com 20 usuários simultâneos.

- A. Aumentar o pool principal para 20 conexões (o PostgreSQL local aceita 100); medir a espera por conexão no script de carga
- B. Criar um pool só de leitura para as consultas da lista e do detalhe da U4, com 12 conexões, separado do pool principal de 10; medir a espera dos dois pools
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
