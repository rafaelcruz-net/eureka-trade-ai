# Image of the one-off db-bootstrap tasks in the cloud (261113-trading-real-bot Environment Provisioning,
# infra/terraform/modules/services/one-off.tf). The same postgres image as docker-compose.yml, with the versioned role
# scripts baked in (locally they are mounted). Built and pushed by the founder to <account>.dkr.ecr.<region>.amazonaws.com
# /<name>/db-bootstrap:<commit SHA>; no secret is ever in the image: every login and password comes from the task.
FROM postgres:17-alpine@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b
COPY bootstrap.sh 10-roles.sql 20-txsigner.sql /bootstrap/
USER postgres
ENTRYPOINT ["sh", "/bootstrap/bootstrap.sh"]
