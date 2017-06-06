#!/bin/bash -e

set -x

# Check if Zookeper, Kafka and riak are ready
while true; do
    `nc -N $DH_ZK_ADDRESS $DH_ZK_PORT`
    result_zk=$?
    `nc -N $DH_KAFKA_ADDRESS $DH_KAFKA_PORT`
    result_kafka=$?
    `curl --output /dev/null --silent --head --fail "http://${DH_RIAK_HOST_MEMBER}:${DH_RIAK_HTTP_PORT}/ping"`
    result_riak=$?

    if [ "$result_kafka" -eq 0 ] && [ "$result_zk" -eq 0 ] && [ "$result_riak" -eq 0 ]; then
        break
    fi
    sleep 5
done

echo "Setting Riak"
curl -XPUT \
    -H "Content-Type: application/json" \
    -H 'x-riak-index-login_bin: dhadmin' \
    -d "{\"id\": 1, \"login\":\"dhadmin\", \"passwordHash\":\"DFXFrZ8VQIkOYECScBbBwsYinj+o8IlaLsRQ81wO+l8=\", \"passwordSalt\":\"sjQbZgcCmFxqTV4CCmGwpIHO\", \"role\":\"ADMIN\", \"status\":\"ACTIVE\", \"loginAttempts\":0, \"lastLogin\":null,\"entityVersion\":0,\"data\":null}" \
        http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/default/buckets/user/keys/1

curl -XPOST \
    -H "Content-Type: application/json" \
    -d '{"increment": 100}' \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/counters/buckets/dh_counters/datatypes/userCounter


curl -XPUT \
    -H "Content-Type: application/json" \
    -H 'x-riak-index-label_bin: Access Key for dhadmin' \
    -H 'x-riak-index-userId_int: 1' \
    -H 'x-riak-index-key_bin: 1jwKgLYi/CdfBTI9KByfYxwyQ6HUIEfnGSgakdpFjgk=' \
    -H 'x-riak-index-expirationDate_int: -1' \
    -d "{\"id\": 1, \"label\":\"Access Key for dhadmin\", \"key\": \"1jwKgLYi/CdfBTI9KByfYxwyQ6HUIEfnGSgakdpFjgk=\", \"expirationDate\": null, \"type\":\"DEFAULT\", \"user\":{\"id\":1,\"login\":\"dhadmin\",\"passwordHash\":\"DFXFrZ8VQIkOYECScBbBwsYinj+o8IlaLsRQ81wO+l8=\",\"passwordSalt\":\"sjQbZgcCmFxqTV4CCmGwpIHO\",\"loginAttempts\":0,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"lastLogin\":null,\"entityVersion\":0,\"data\":null}, \"permissions\": [{\"id\":null,\"domains\":null,\"subnets\":null,\"actions\":null,\"networkIds\":null,\"deviceIds\":null}]}" \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/default/buckets/access_key/keys/1

curl -XPOST \
    -H "Content-Type: application/json" \
    -d '{"increment": 100}' \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/counters/buckets/dh_counters/datatypes/accessKeyCounter

curl -XPUT \
    -H "Content-Type: application/json" \
    -H 'x-riak-index-name_bin: VirtualLed Sample Network' \
    -d "{\"id\":1,\"key\":null,\"name\":\"VirtualLed Sample Network\", \"description\":\"A DeviceHive network for VirtualLed sample\",\"entityVersion\":null}" \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/default/buckets/network/keys/1

curl -XPOST \
    -H "Content-Type: application/json" \
    -d '{"increment": 100}' \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/counters/buckets/dh_counters/datatypes/networkCounter

curl -XPUT \
    -H "Content-Type: application/json" \
    -H 'x-riak-index-device_id_bin: e50d6085-2aba-48e9-b1c3-73c673e414be' \
    -d "{\"id\":1, \"deviceId\":\"e50d6085-2aba-48e9-b1c3-73c673e414be\", \"name\":\"Sample VirtualLed Device\", \"status\":\"Offline\", \"network\": {\"id\":1,\"key\":null,\"name\":\"VirtualLed Sample Network\", \"description\":\"A DeviceHive network for VirtualLed sample\",\"entityVersion\":null}, \"blocked\":null}" \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/default/buckets/device/keys/1

curl -XPOST \
    -H "Content-Type: application/json" \
    -d '{"increment": 100}' \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/counters/buckets/dh_counters/datatypes/deviceCounter

curl -XPUT \
    -H "Content-Type: application/json" \
    -d "{\"value\":\"true\"}" \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/default/buckets/configuration/keys/user.anonymous_creation

curl -XPUT \
    -H "Content-Type: application/json" \
    -d "{\"value\":1000}" \
    http://${DH_RIAK_HOST}:${DH_RIAK_HTTP_PORT}/types/default/buckets/configuration/keys/user.login.lastTimeout

echo "Starting DeviceHive"
java -server -Xmx512m -XX:MaxRAMFraction=1 -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70 -XX:+ScavengeBeforeFullGC -XX:+CMSScavengeBeforeRemark -jar \
-Dflyway.enabled=false \
-Driak.host=${DH_RIAK_HOST} \
-Driak.port=${DH_RIAK_PORT} \
-Dbootstrap.servers=${DH_KAFKA_ADDRESS}:${DH_KAFKA_PORT} \
-Dzookeeper.connect=${DH_ZK_ADDRESS}:${DH_ZK_PORT} \
-Dhazelcast.port=${DH_HAZELCAST_PORT:-5701} \
-Drpc.server.request-consumer.threads=${DH_RPC_SERVER_REQ_CONS_THREADS:-1} \
-Drpc.server.worker.threads=${DH_RPC_SERVER_WORKER_THREADS:-1} \
-Drpc.server.disruptor.wait-strategy=${DH_RPC_SERVER_DISR_WAIT_STRATEGY:-blocking} \
./devicehive-backend-${DH_VERSION}-boot.jar

set +x