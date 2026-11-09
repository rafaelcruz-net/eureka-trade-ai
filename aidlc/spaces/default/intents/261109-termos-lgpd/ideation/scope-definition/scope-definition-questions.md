# Perguntas da Definição de Escopo — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/feasibility/feasibility-assessment.md`, `constraint-register.md` e `raid-log.md`.

Capacidades levantadas até aqui:
- C1: termos de uso v2 e política de privacidade, textos versionados, com novo aceite;
- C2: páginas públicas `/termos` e `/privacidade`, com links no rodapé e no convite;
- C3: tabela de retenção ligada aos expurgos reais, com testes;
- C4: pedidos de titular (formulário na Conta, registro com prazo, atendimento no administrador);
- C5: exclusão por anonimização;
- C6: campos de implantação (local de armazenamento, transferência internacional, e-mail do encarregado).

## Q1. Qual o escopo mínimo que entrega valor?

A) C1 + C2 + C3 + C6: com isso, o convidado lê e aceita documentos fiéis ao sistema, e o beta pode abrir (recomendado)
B) Só C1 + C2
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q2. O que é Must e o que é Should?

A) Must:
- C1, C2, C3 e C6;
- C4, porque a LGPD exige atender os pedidos em até 15 dias;
- C5, porque é a forma de cumprir a exclusão sem quebrar o ledger.

Should: alerta de prazo dos pedidos no app do administrador. Could: histórico das versões anteriores dos documentos publicado na página (recomendado)

B) C4 e C5 como Should, atendidos à mão no começo
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q3. Quais as dependências entre as capacidades?

A) C2 depende de C1. C3 alimenta a seção de retenção de C1. C5 depende de C4, porque a exclusão nasce de um pedido. C6 é preenchido na implantação e bloqueia só a publicação da versão do beta (recomendado)
B) Not identified
X) Other (please specify)

[Answer]: A

## Q4. Qual a ordem preferida?

A) Por dependência e risco: primeiro C1, C3 e C2, que são os documentos fiéis ao sistema; depois C4 e C5, onde está o maior risco técnico; por último C6, na implantação (recomendado)
B) Por risco primeiro (C5 antes de tudo)
C) Not yet defined
X) Other (please specify)

[Answer]: A

## Q5. Há prazo fixo para alguma capacidade?

A) Não há data fixa. Tudo precisa estar pronto antes da abertura do beta (recomendado)
B) Sim (diga qual)
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Escopo mínimo: C1 (termos v2 e política), C2 (páginas públicas), C3 (tabela de retenção) e C6 (campos de implantação).
- Must: C1 a C6. Should: alerta de prazo dos pedidos. Could: histórico das versões anteriores publicado.
- Dependências: C2 depende de C1; C3 alimenta C1; C5 depende de C4; C6 bloqueia só a publicação da versão do beta.
- Ordem: C1, C3 e C2; depois C4 e C5; por último C6, na implantação.
- Prazo: sem data fixa; tudo antes da abertura do beta.

- Looks correct
- Request changes

[Answer]: Looks correct
