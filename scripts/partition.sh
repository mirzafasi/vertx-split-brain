#!/usr/bin/env bash
# Split a 4-node docker-compose cluster into {node1,node2} vs {node3,node4}
# by blocking Hazelcast port 5701 between the two groups, leaving HTTP (8080) intact.
#
# Usage:
#   ./scripts/partition.sh split      # cause the split-brain
#   ./scripts/partition.sh heal       # remove the iptables rules
#   ./scripts/partition.sh status     # show what each node thinks
set -euo pipefail

HZ_PORT=5701
GROUP_A=(node1 node2)
GROUP_B=(node3 node4)

block() {
  local from=$1 to=$2
  echo "  blocking $from -> $to:$HZ_PORT"
  # We resolve hostnames to IPs inside the container running iptables.
  local to_ip
  to_ip=$(docker exec "$from" getent hosts "$to" | awk '{print $1}')
  docker exec "$from" iptables -A OUTPUT -p tcp -d "$to_ip" --dport $HZ_PORT -j DROP
  docker exec "$from" iptables -A INPUT  -p tcp -s "$to_ip" --dport $HZ_PORT -j DROP
}

unblock_all() {
  for n in "${GROUP_A[@]}" "${GROUP_B[@]}"; do
    echo "  flushing iptables on $n"
    docker exec "$n" iptables -F
  done
}

split() {
  echo "Causing split-brain: ${GROUP_A[*]} | ${GROUP_B[*]}"
  for a in "${GROUP_A[@]}"; do
    for b in "${GROUP_B[@]}"; do
      block "$a" "$b"
      block "$b" "$a"
    done
  done
  echo "Done. Watch logs with: docker compose logs -f node1 node3"
  echo "Or curl status:        curl localhost:8081/sb/status | jq"
}

heal() {
  echo "Healing partition..."
  unblock_all
  echo "Done. Hazelcast will eventually fire LifecycleEvent.MERGED on each node."
}

status() {
  for port in 8081 8082 8083 8084; do
    echo "--- localhost:$port ---"
    curl -s "localhost:$port/sb/status" | jq -c . || echo "(unreachable)"
  done
}

case "${1:-}" in
  split)  split  ;;
  heal)   heal   ;;
  status) status ;;
  *) echo "usage: $0 {split|heal|status}"; exit 1 ;;
esac
