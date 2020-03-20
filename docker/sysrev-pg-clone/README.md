# sysrev-pg-clone:1.0
Provides a local container that clones the latest snapshot of the sysrev database

## Run
```
`aws ecr get-login --no-include-pw`
docker pull 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone
docker run --rm --name pg-sysrev -e POSTGRES_HOST_AUTH_METHOD=trust -it -p 5432:5432
```

## To build
To build the image locally:
1. run `../../scripts/pull-latest-backup`
2. move the .pgdumpc file you get to `backup.pgdumpc`
2. `docker build -t 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<date> .`
3. run it  
```
docker run -it --rm --name sysrev-pg-clone\
 -e POSTGRES_HOST_AUTH_METHOD=trust\
 -p 5432:5432 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<date>
 ```
3. `docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev`
4. `docker exec sysrev-pg-clone createdb -h localhost -U postgres -T template0 sysrev_test`
5. `docker exec sysrev-pg-clone createuser -h localhost -U postgres readaccess`
6. `docker exec sysrev-pg-clone pg_restore -d sysrev -1 -U postgres --format=custom --disable-triggers backup.pgdumpc`
7. `docker commit sysrev-pg-clone 951523641836.dkr.ecr.us-east-1.amazonaws.com/co.insilica/sysrev-pg-clone:<date>`

# TODO
This is a bit silly and creates a huge container.  Wouldn't it be better to just create a simple testing database?  Probably can just create an empty sysrev database from flyway.