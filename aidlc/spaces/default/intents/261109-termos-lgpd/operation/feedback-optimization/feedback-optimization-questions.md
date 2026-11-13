# Perguntas do Feedback & Optimization

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10). Não há produção nem usuários reais ainda: o beta PAPER não foi aberto e o único ambiente é o local.

## Pergunta 1
Os SLOs estão sendo cumpridos?

A) Registrar a conformidade com as evidências que existem (CI, smoke, cargas locais) e deixar a medição contínua para quando o beta rodar num ambiente com monitoramento (recomendado: sem tráfego real não há orçamento de erro a consumir)
B) Simular tráfego contínuo localmente para medir o orçamento
X) Other (please specify)

[Answer]: A

## Pergunta 2
Oportunidades de custo?

A) Sem nuvem não há custo de infraestrutura; olhar os minutos do GitHub Actions, em que o job `dependencies` é o mais longo (cerca de 1 h sem a chave da NVD) (recomendado)
B) Estimar já o custo da AWS
X) Other (please specify)

[Answer]: A

## Pergunta 3
Desvios de configuração ou de infraestrutura?

A) Comparar o que foi desenhado nas Units (`infrastructure-specification.md`, `monitoring-design.md`, `cicd-pipeline.md`) com o que foi entregue e registrar cada diferença com o motivo (recomendado)
B) Não registrar
X) Other (please specify)

[Answer]: A

## Pergunta 4
O que levar para o próximo ciclo de ideação?

A) As pendências com prazo (Spring Boot 4 antes de 2027-02-09), os bloqueios do beta (encarregado, campos de implantação, publicação da v2, AWS) e as lições de operação deste intent (recomendado)
B) Só as pendências com prazo
X) Other (please specify)

[Answer]: A

## Pergunta 5
Que trabalho manual automatizar?

A) Os passos que hoje dependem do responsável por serem protegidos ou interativos (patches de workflow, `verify.sh` local) continuam manuais por decisão; automatizar a checagem de que a CI disparou depois de cada push e o vencimento das exceções de segurança (recomendado)
B) Nada
X) Other (please specify)

[Answer]: A
