# Notification Service — Load Tests

## Install k6 on Mac

```bash
brew install k6
```

Verify:

```bash
k6 version
```

---

## Run each scenario separately

All three scenarios live in one file. Use `--scenario` to run only one at a time.

### Scenario 1 — Broadcast flood

50 virtual users, each sends a 10-recipient broadcast every second for 2 minutes.

```bash
k6 run --scenario broadcast_flood load-test/notification-load-test.js
```

Expected throughput: ~50 req/s sustained over 2 minutes.

### Scenario 2 — Notification fetch

100 virtual users, each fetches the first page of notifications every 2 seconds for 2 minutes.

```bash
k6 run --scenario notification_fetch load-test/notification-load-test.js
```

Expected throughput: ~50 req/s. Watch `approx_cache_hits` rise as Redis warms up over the first 10–15 seconds.

### Scenario 3 — Spike test

Ramps from 10 to 500 virtual users in 10 seconds, holds for 30 seconds, then drops back to 10.

```bash
k6 run --scenario spike_test load-test/notification-load-test.js
```

Watch the p95 latency during the hold phase. A healthy service recovers to sub-200 ms before the ramp-down begins.

### Run all three together

```bash
k6 run load-test/notification-load-test.js
```

This runs all scenarios simultaneously (~660 peak VUs). Use this for a full-system stress test.

---

## How to read the results

k6 prints a summary after every run. The key lines:

```
http_req_duration............: avg=45ms  min=3ms  med=32ms  max=890ms  p(90)=120ms  p(95)=180ms
http_req_failed..............: 0.12%     ✓ passes threshold rate<0.01
checks......................: 99.88%    ✓ passes
```

### Thresholds

The test defines two thresholds that must pass for a green build:

| Threshold | Target | What it means |
|-----------|--------|---------------|
| `http_req_duration p(95)` | < 200 ms | 95 % of all requests complete within 200 ms |
| `http_req_failed rate` | < 1 % | Fewer than 1 in 100 requests return a non-2xx status |

A threshold failure prints `✗` and k6 exits with code 99.

### Custom metrics to focus on

| Metric | What it tells you |
|--------|-------------------|
| `approx_cache_hits` | Responses under 15 ms — a proxy for Redis cache hits. Should climb quickly as the fetch scenario warms Redis. |
| `broadcast_duration_ms p(95)` | Per-scenario 95th-percentile for the broadcast path only. |
| `fetch_duration_ms p(95)` | Per-scenario 95th-percentile for the fetch path only. |
| `spike_duration_ms p(95)` | Same, scoped to the spike scenario. |
| `broadcast_errors_total` | Absolute count of failed broadcasts (non-202 responses). |
| `fetch_errors_total` | Absolute count of failed fetches (non-200 responses). |

### Output to a file

```bash
k6 run --out json=results.json load-test/notification-load-test.js
```

Then query it with `jq`:

```bash
# All p95 values from the run
jq 'select(.type=="Point" and .metric=="http_req_duration") | .data.value' results.json | \
  awk 'BEGIN{n=0;s=0}{n++;a[n]=$1}END{asort(a);print "p95:", a[int(n*0.95)]}'
```

---

## What to watch in service logs during the test

Run the service with:

```bash
./mvnw spring-boot:run
```

or tail its container:

```bash
docker logs -f notification-service
```

### Broadcast flood — what to watch

```
INFO  NotificationProducer : Published to stream notifications_stream id=...
INFO  NotificationWorker   : Processing record ... for 10 recipient(s)
```

- Steady stream of `Published` lines = stream is absorbing load.
- Any `ERROR ... sending to DLQ` = worker is rejecting messages. Check payload validation.
- `WARN  Redis unavailable` during the test = Redis is the bottleneck, not the app.

### Notification fetch — cache warm-up

```
INFO  CacheService : Cache miss for user-XX — querying MongoDB
INFO  CacheService : Cache hit  for user-XX
```

- After the first ~30 seconds you should see cache hits outnumber misses.
- Persistent `Cache miss` for the same user IDs = Redis TTL too short or eviction under memory pressure.

### Spike test — recovery signals

```
WARN  NotificationWorker : Retry 1/3 for record ...
ERROR NotificationWorker : Max retries reached — sending to DLQ
```

- A few retries during the ramp-up are expected under sudden load.
- If DLQ entries appear consistently during the hold phase the worker thread pool is saturated.
- Watch for `WARN Redis` — under heavy write load the stream backlog can grow. XPENDING count will rise if workers fall behind.

### MongoDB pressure

```
WARN  MongoTemplate : Command failed with error ... connection timed out
```

- Means MongoDB is the bottleneck during the spike. The service returns 503 in this case, which will be visible as errors in k6's `http_req_failed` counter.

### Health endpoint during the test

```bash
watch -n 2 curl -s http://localhost:8085/actuator/health
```

- `"status":"UP"` — service is healthy.
- `"status":"DOWN"` on the `redis` or `mongo` component — external dependency failed. k6 error rate will spike immediately.
