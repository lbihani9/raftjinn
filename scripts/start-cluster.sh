#!/bin/bash

num_nodes=3
all_nodes=""

for (( i=1; i<=num_nodes; i++ ))
do
  port=$((9000 + i))
  all_nodes+="node$i:localhost:$port"
  if [[ $i -lt $num_nodes ]]; then
    all_nodes+=","
  fi
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR="$SCRIPT_DIR/../server"

mvn -f "$PROJECT_DIR" exec:java -Dexec.mainClass='org.jinn.RaftNodeRunner' -Dexec.args="$node_id $port $all_nodes"

for (( i=1; i<=num_nodes; i++ ))
do
  node_id="node$i"
  port=$((9000 + i))

done

wait
