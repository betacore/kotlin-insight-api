#!/bin/bash
index=1
result=0
echo "### Checking Jira is running"
while [ $result -eq 0 ]
do
  response=$(curl http://localhost:8080/status 2> /dev/null)
  if [ "$response" == '{"state":"RUNNING"}' ]
  then
    result=1
  fi
  if [ $index -ge 60 ]
  then
    echo "!!! JIRA NOT RUNNING AFTER 15 SECONDS"
    exit 1
  fi
  sleep 1
  echo -e "\t### WAITING FOR JIRA"
  index=$((index+1))
done
echo "### JIRA IS UP"
