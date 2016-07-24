export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
/opt/spark/bin/spark-submit \
    --master yarn \
    --deploy-mode cluster \
    --driver-memory 4G \
    --executor-memory 4G \
    --executor-cores 16 \
    --conf spark.executor.instances=2 \
    --conf spark.driver.cores=16 \
    --conf spark.yarn.executor.memoryOverhead=2048 \
    --conf spark.yarn.driver.memoryOverhead=2048 \
    core/target/scala-2.11/sysrev-fingerprint_assembly.jar
