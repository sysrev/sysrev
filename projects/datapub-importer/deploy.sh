set -e

./uberjar.sh
rsync target/datapub-importer.jar ubuntu@sysrev.com:/home/ubuntu/datapub/datapub-importer.jar --progress
