#!/bin/bash

ORIGDIR=$(pwd)
BASEDIR="$(dirname "$(readlink -e "$0")")"
BASEDIR=${BASEDIR%/*} # Use parent of bin directory

function usage {
  echo -e "Usage: $0 <Options> <Job arguments>\n\
Options:\n\
  --master <URL>                    specify spark master. Required!\n\
  --total-executor-cores <NUMBER>   number of cores to use. Required!\n\
  --help                            display this usage information\n\
"
}

export SPARK_HOME=$BASEDIR/lib/spark-2.2.0-bin-hadoop2.7
export YARN_CONF_DIR=$BASEDIR/conf
export HADOOP_USER_NAME=hdfs

# find spark-submit
if [ ! -z $SPARK_HOME ]; then
  SPARK_SUBMIT="$SPARK_HOME/bin/spark-submit"
else
  SPARK_SUBMIT=$(which spark-submit)
fi

if [ -z "$SPARK_SUBMIT" ]; then
  echo "ERROR: Either have spark-submit on the PATH or SPARK_HOME must be set. Exiting."
  usage
  exit 1
fi

ARGUMENTS=()
# find spark master argument
while [[ $# > 0 ]]; do
  if [[ $1 == "--master" ]]; then
    SPARK_MASTER=$2
    shift 2
  elif [[ $1 == "--total-executor-cores" ]]; then
    CORES=$2
    shift 2
  elif [[ $1 == "--local-mode" ]]; then
    LOCAL_MODE=true
    shift 1 
  elif [[ $1 == "--help" ]]; then
    usage
    exit 0
  else
    ARGUMENTS+=("$1")
    shift 1
  fi
done

if [ -z "$CORES" ]; then
  echo "ERROR: Need number of cores to be specifies at cli with --total-executor-cores <NUMBER> . Exiting."
  usage
  exit 3
fi

if [ $LOCAL_MODE = true ]; then
  export SPARK_MASTER_HOST="127.0.0.1"
  $SPARK_HOME/sbin/start-master.sh
  $SPARK_HOME/sbin/start-slave.sh spark://127.0.0.1:7077 
  exec $SPARK_SUBMIT --packages org.apache.spark:spark-sql-kafka-0-10_2.11:2.2.0 \
	--master spark://127.0.0.1:7077 \
	--driver-memory 512m \
	--executor-memory 512m \
	--total-executor-cores $CORES \
	--files $BASEDIR/conf/log4j.properties,$BASEDIR/conf/facility-state-job-local.yaml \
	--class org.scray.example.FacilityStateJob \
	target/facility-state-job-1.0-SNAPSHOT-jar-with-dependencies.jar \
	-c file://$BASEDIR/conf/facility-state-job-local.yaml ${ARGUMENTS[@]}
else
  exec $SPARK_SUBMIT --packages org.apache.spark:spark-sql-kafka-0-10_2.11:2.2.0 --master yarn --driver-memory 512m --executor-memory 512m --total-executor-cores $CORES  --files $BASEDIR/../conf/log4j.properties,$BASEDIR/../conf/facility-state-job-local.yaml --class org.scray.example.FacilityStateJob target/facility-state-job-1.0-SNAPSHOT-jar-with-dependencies.jar ${ARGUMENTS[@]}
fi


cd $ORIGDIR