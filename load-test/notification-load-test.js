import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Config ────────────────────────────────────────────────────────────────────

const BASE_URL       = 'http://localhost:8085';
const INTERNAL_TOKEN = 'dev-only-change-in-production';
const ORG_ID         = 'org-abc';
const TOTAL_USERS    = 100;

// ── Custom metrics ────────────────────────────────────────────────────────────

// Counts responses whose duration suggests a Redis cache hit (< 15 ms).
// Not a guarantee — it is a strong signal when consistently higher than
// the first-request baseline.
const approxCacheHits  = new Counter('approx_cache_hits');
const broadcastTotal   = new Counter('broadcast_requests_total');
const fetchTotal       = new Counter('fetch_requests_total');
const broadcastErrors  = new Counter('broadcast_errors_total');
const fetchErrors      = new Counter('fetch_errors_total');

// Per-scenario p95 trends for the summary table
const broadcastDuration = new Trend('broadcast_duration_ms', true);
const fetchDuration     = new Trend('fetch_duration_ms',     true);
const spikeDuration     = new Trend('spike_duration_ms',     true);

// ── Scenarios ─────────────────────────────────────────────────────────────────

export const options = {
  scenarios: {

    // 1. Broadcast flood — 50 VUs, one broadcast per second for 2 minutes
    broadcast_flood: {
      executor: 'constant-vus',
      vus:      50,
      duration: '2m',
      exec:     'broadcastFlood',
    },

    // 2. Notification fetch — 100 VUs, one fetch per 2 seconds for 2 minutes
    notification_fetch: {
      executor: 'constant-vus',
      vus:      100,
      duration: '2m',
      exec:     'notificationFetch',
    },

    // 3. Spike test — 10 → 500 VUs in 10 s, hold 30 s, back to 10
    spike_test: {
      executor:  'ramping-vus',
      startVUs:  10,
      stages: [
        { duration: '10s', target: 500 },
        { duration: '30s', target: 500 },
        { duration: '10s', target: 10  },
      ],
      exec: 'spikeTest',
    },
  },

  thresholds: {
    // Global — every request from every scenario must meet these
    http_req_duration: ['p(95)<200'],
    http_req_failed:   ['rate<0.01'],

    // Per-scenario — tagged automatically by k6 with the scenario name
    'http_req_duration{scenario:broadcast_flood}':   ['p(95)<200'],
    'http_req_duration{scenario:notification_fetch}': ['p(95)<200'],
    'http_req_duration{scenario:spike_test}':         ['p(95)<200'],

    // Custom metric thresholds
    'broadcast_errors_total': ['count<100'],
    'fetch_errors_total':     ['count<100'],
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────

function randomUserId() {
  return `user-${Math.floor(Math.random() * TOTAL_USERS) + 1}`;
}

// Picks `count` distinct user IDs without repetition.
function pickRecipients(count) {
  const ids = new Set();
  while (ids.size < count) {
    ids.add(randomUserId());
  }
  return Array.from(ids);
}

function broadcastPayload(recipientCount, tier = 'HIGH') {
  return JSON.stringify({
    recipientIds:   pickRecipients(recipientCount),
    organizationId: ORG_ID,
    tier,
    type:           'SURVEY',
    title:          `k6 load test — ${tier}`,
    description:    'Automated load test notification from k6.',
    redirectUri:    'https://example.com/load-test',
    channels:       ['IN_APP'],
  });
}

const broadcastHeaders = {
  'Content-Type':    'application/json',
  'X-Internal-Token': INTERNAL_TOKEN,
};

// ── Scenario 1 — Broadcast flood ──────────────────────────────────────────────

export function broadcastFlood() {
  const res = http.post(
    `${BASE_URL}/api/internal/notifications/broadcast`,
    broadcastPayload(10),
    { headers: broadcastHeaders, tags: { name: 'broadcast' } },
  );

  broadcastTotal.add(1);
  broadcastDuration.add(res.timings.duration);

  const ok = check(res, {
    'broadcast: 200 OK':              (r) => r.status === 200,
    'broadcast: under 200 ms':        (r) => r.timings.duration < 200,
    'broadcast: body status=SUCCESS': (r) => {
      try { return JSON.parse(r.body).status === 'SUCCESS'; } catch { return false; }
    },
  });

  if (!ok) broadcastErrors.add(1);

  // Target: one broadcast per second per VU (50 req/s total)
  sleep(1);
}

// ── Scenario 2 — Notification fetch ──────────────────────────────────────────

export function notificationFetch() {
  const userId = randomUserId();

  const res = http.get(
    `${BASE_URL}/api/notifications?page=1&limit=10`,
    {
      headers: { 'X-User-Id': userId },
      tags:    { name: 'fetch_notifications' },
    },
  );

  fetchTotal.add(1);
  fetchDuration.add(res.timings.duration);

  const ok = check(res, {
    'fetch: 200 OK':             (r) => r.status === 200,
    'fetch: under 200 ms':       (r) => r.timings.duration < 200,
    'fetch: body has data field': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data !== undefined;
      } catch {
        return false;
      }
    },
  });

  if (!ok) fetchErrors.add(1);

  // Responses under 15 ms are almost certainly served from Redis —
  // MongoDB round-trips rarely fall below that under any load.
  if (res.timings.duration < 15) {
    approxCacheHits.add(1);
  }

  // Target: one fetch per 2 seconds per VU (50 req/s total)
  sleep(2);
}

// ── Scenario 3 — Spike test ───────────────────────────────────────────────────

export function spikeTest() {
  const userId = randomUserId();

  // 30 % writes / 70 % reads — mirrors realistic traffic mix during a spike.
  if (Math.random() < 0.3) {
    const res = http.post(
      `${BASE_URL}/api/internal/notifications/broadcast`,
      broadcastPayload(5, 'MEDIUM'),
      { headers: broadcastHeaders, tags: { name: 'spike_broadcast' } },
    );

    spikeDuration.add(res.timings.duration);

    check(res, {
      'spike broadcast: 200 OK': (r) => r.status === 200,
    });
  } else {
    const res = http.get(
      `${BASE_URL}/api/notifications?page=1&limit=10`,
      {
        headers: { 'X-User-Id': userId },
        tags:    { name: 'spike_fetch' },
      },
    );

    spikeDuration.add(res.timings.duration);

    check(res, {
      'spike fetch: 200 OK': (r) => r.status === 200,
    });
  }

  // Shorter sleep to generate a sharper spike
  sleep(0.5);
}
