# Perguntas do Deployment Pipeline

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10). Base: o Deployment Pipeline do intent `261026-memetrade-saas-mvp` (recreate local hoje, blue/green desenhado para a AWS), que continua valendo; aqui ficam só as decisões deste intent.

Situação: continua sem conta de nuvem, região nem orçamento (C-10, C-12). O único ambiente é o local, com Docker Compose. Este intent acrescenta as migrações `0012` a `0015`, a chave HMAC obrigatória da trilha (U2), o catálogo de termos na imagem (U1) e a anonimização irreversível (U5).

## Pergunta 1
Qual estratégia de implantação?

A) A mesma do intent anterior: recreate local hoje; blue/green na AWS quando ela existir (recomendado: nada neste intent muda o raciocínio)
B) Rolling ou canary
X) Other (please specify)

[Answer]: A

## Pergunta 2
Quais portões de promoção entre ambientes?

A) Os mesmos: merge em `main` com a CI e o `security.yml` verdes; staging automático (futuro); produção do beta PAPER com tag de release e aprovação do responsável; REAL bloqueado até G2 e G3 com revisor independente (recomendado: `team.md`)
B) Outra regra
X) Other (please specify)

[Answer]: A

## Pergunta 3
Como fica o rollback depois da primeira anonimização (U5)?

A) Piso de rollback: depois da primeira anonimização, nenhuma imagem anterior à U5 volta; só correção para frente. O registro da release diz isso, e o ensaio de rollback acontece antes de qualquer anonimização real (recomendado: é o que o `cicd-pipeline.md` da U5 define)
B) Permitir rollback para antes da U5 mesmo depois de anonimizar
X) Other (please specify)

[Answer]: A

## Pergunta 4
Como publicar uma versão nova dos termos (que ativa a trava de novo aceite para todos)?

A) Como uma release: a pasta nova e revisada entra no catálogo da imagem, segue o checklist de `docs/api/u1-legal-documents.md` e só é publicada com os campos de implantação preenchidos (recomendado)
B) Por configuração em tempo de execução, sem nova imagem
X) Other (please specify)

[Answer]: A

## Pergunta 5
Feature flags?

A) Sem serviço de flags no beta; REAL desligado por configuração padrão; kill switch da U8 como chave operacional; um serviço gerenciado (por exemplo, AppConfig) fica para quando houver AWS (recomendado)
B) Adotar um serviço de flags agora
X) Other (please specify)

[Answer]: A
