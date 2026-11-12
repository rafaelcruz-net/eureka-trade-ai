# Perguntas do Environment Provisioning

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10). Situação: continua sem conta de nuvem, região nem orçamento (C-10, C-12); a decisão do intent anterior (inventariar e validar o ambiente local, AWS pendente) segue valendo.

## Pergunta 1
O que provisionar neste intent?

A) Nada na nuvem: validar o ambiente local e o da CI com as mudanças deste intent e registrar a AWS como pendente (recomendado: não há conta, região nem orçamento)
B) Criar a conta e os ambientes da AWS agora
X) Other (please specify)

[Answer]: A

## Pergunta 2
Rede (VPC, sub-redes, grupos de segurança, NACLs)?

A) Nada muda: o Compose publica portas só em `127.0.0.1`; o desenho de rede da AWS do intent anterior continua pendente (recomendado)
B) Revisar o desenho de rede agora
X) Other (please specify)

[Answer]: A

## Pergunta 3
Segredos e parâmetros?

A) A chave `MEMETRADE_AUDIT_WALLET_HMAC_KEY` entra como segredo: efêmera no `verify.sh`, no `load-test.sh` e na CI; no `.env` local para o ambiente de desenvolvimento; no Secrets Manager na AWS. Os campos de implantação dos documentos legais (armazenamento, transferência, encarregado) entram como parâmetros, não segredos (recomendado)
B) Outra forma
X) Other (please specify)

[Answer]: A

## Pergunta 4
Conectividade entre contas ou VPCs?

A) Não se aplica: um único ambiente local e nenhuma conta de nuvem (recomendado)
B) Outra
X) Other (please specify)

[Answer]: A
