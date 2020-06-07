# sysrev-pg-clone:1.0
Provides a local container that clones the latest snapshot of the sysrev database

## Run

1. First create a volume for your postgres data:
```
> docker volume create --driver local \
    --opt type=none \
    --opt device=<LOCATION FOR YOUR POSTGRES DATA> \
    --opt o=bind \
    sysrev-pg-volume
```
* Note that you will need write privileges at this location.  Sometimes external drives have issues when they are formatted for an os incompatible with your own.  If this is an issue, you will get a message about permissions on /var/lib/postgresql.

2. Pull in and run the container
```
> `aws ecr get-login --no-include-pw`
> docker pull 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:3
> docker run --rm -it \
     --name sysrev-pg-clone \
     -e POSTGRES_PASSWORD=postgres \
     -e POSTGRES_HOST_AUTH_METHOD=trust \
     -e PGDATA=/var/lib/postgresql/data/sysrev \
     -v sysrev-pg-volume:/var/lib/postgresql/data \
     951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:3
```

3. download the latest backup  
`> ../../scripts/pull-latest-backup`

4. copy the pdump file into the container  
`> docker cp ./backup.pgdumpc sysrev-pg-clone:/backup.pgdumpc`

5. restore the backup (have like 100 gb available). This will take a while depending on your write speed.  
```
> docker exec sysrev-pg-clone pg_restore \
	 -d sysrev -1 -U postgres --format=custom \
	 --disable-triggers backup.pgdumpc
```

6. You can now use this db from the sysrev repl.  

## To build
This image is a simple extension of the `postgres:12` image.  To build the image locally:
1. create a container from `postgres:12` image.
```
docker run -it --rm --name sysrev-pg-clone \
 -e POSTGRES_HOST_AUTH_METHOD=trust \
 -e POSTGRES_PASSWORD=postgress \
 -e POSTGRES_HOST_AUTH_METHOD=trust \
 -e PGDATA=/var/lib/postgresql/data/sysrev \
 -v /home/thomas/external/postgres:/var/lib/postgresql/data \
 -p 5432:5432 postgres:12
 ```
3. `docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev`
4. `docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev_test`
5. `docker exec sysrev-pg-clone createuser -h localhost -U postgres readaccess`
7. `docker commit sysrev-pg-clone 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<version>`
8. `docker push 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<version>`