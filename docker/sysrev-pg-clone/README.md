# Create a postgres docker container with a sysrev clone

1. First create a volume for your postgres data:
```
> docker volume create --driver local \
    --opt type=none \
    --opt device=<location> \
    --opt o=bind \
    sysrev-pg-volume
```
* Note: you need write privileges at <location>. Make sure you can `chown -R user:docker <location`

2. run a postgres container - name it sysrev-pg-clone
```
docker run -it --rm --name sysrev-pg-clone \
 --shm-size 6g \
 -e POSTGRES_HOST_AUTH_METHOD=trust \
 -e POSTGRES_PASSWORD=postgress \
 -e POSTGRES_HOST_AUTH_METHOD=trust \
 -e PGDATA=/var/lib/postgresql/data/sysrev \
 -v sysrev-pg-volume:/var/lib/postgresql/data \
 -p 5432:5432 postgres:12
 ```
3. `docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev`
4. `docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev_test`
5. `docker exec sysrev-pg-clone createuser -h localhost -U postgres readaccess`
7. `docker commit sysrev-pg-clone 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<version>`
8. `docker push 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<version>`

9. download the latest backup  
`> ../../scripts/pull-latest-backup`

10. copy the pdump file into the container  
`> docker cp ./backup.pgdumpc sysrev-pg-clone:/backup.pgdumpc`

11. restore the backup (have like 100 gb available). This will take a while depending on your write speed.  
```
> docker exec sysrev-pg-clone pg_restore \
	 -d sysrev -1 -U postgres --format=custom \
	 --disable-triggers backup.pgdumpc
```

12. You can now use this db from the sysrev repl.  

## Overwrite existing database
To overwrite an exisint database starting from step 10 above:

10. copy the pdump file into the container  
`> docker cp ./backup.pgdumpc sysrev-pg-clone:/backup.pgdumpc`

11. drop existing database  
`docker exec sysrev-pg-clone dropdb -h localhost -U postgres sysrev` 

12. create sysrev database  
`docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev`

13. restore database  
```
> docker exec sysrev-pg-clone pg_restore \
	 -d sysrev -1 -U postgres --format=custom \
	 --disable-triggers backup.pgdumpc
```