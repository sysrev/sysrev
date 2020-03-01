FROM postgres:12
ENV SR_DEST_DB sysrev
ENV SR_DEST_PORT 5432
ADD backup.pgdumpc /backup.pgdumpc
RUN mkdir -p /var/lib/postgresql-static/data
ENV PGDATA /var/lib/postgresql-static/data