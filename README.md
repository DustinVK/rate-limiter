# Rate-limiting middleware proof of concept

This project is a Spring MVC rate-limiting interceptor. It runs before controllers and limits each organization in two ways:

- total requests across the whole API; and
- requests to each endpoint.

It uses in-memory token buckets. It was built after one client's sync job consumed about 40% of API capacity for three hours.

> **Important:** this is in-memory only. A restart resets every limit, and each extra application instance multiplies the effective limits. Do not deploy this as a production limiter until you have read [B1 — Failure Modes and Scaling Plan](#b1--failure-modes-and-scaling-plan).

## Quick start

```sh
mvn test                 # 39 tests, about 5s, no sleeps; tests use a fake clock
mvn spring-boot:run      # serves on :8080 using ./ratelimit.yml
./scripts/demo.sh        # shows restart reset and 2 replicas = 2x limit

curl -i -H 'X-Api-Key: key-globex' localhost:8080/items
```

## If a customer is getting 429s

1. **Read the response body.** The `binding_limit` field tells you which limit was hit: `org-total`, `endpoint-read`, `endpoint-write`, or `route:<METHOD path>`. Each rejection is also logged as `rate_limited org=... limit=... request_id=...`. Search the logs for the response body's `request_id`.
2. **Raise the limit that was hit.** In `ratelimit.yml`, either set `ratelimit.orgs.<org>.tier: premium`, or override one limit for that org, for example `write: { per-minute: 40, burst: 10 }`. If the limit is `route:<METHOD path>`, change it under `route-overrides`; route limits apply to every org. Then restart. A restart resets every counter.
3. **Use shadow mode if you need visibility without blocking.** Set `enforce: false`. Would-be rejections are still logged, but requests are not blocked.
4. **Bad config fails fast.** A zero rate, unknown tier, misspelled field, or route override that matches no route stops startup with an error. It never silently means "no limit." The build also loads the real `ratelimit.yml`, so `mvn test` catches these problems before deploy.

### Example 429 response

```http
HTTP/1.1 429
RateLimit: "endpoint-write";r=0;t=3
Retry-After: 3
Cache-Control: no-store
Content-Type: application/json

{"error":"rate_limited","message":"Rate limit 'endpoint-write' exceeded for org 'globex'. Retry after 3s; add jitter to retries.",
 "binding_limit":"endpoint-write","violated_limits":["endpoint-write"],"org_id":"globex","tier":"standard",
 "limit":{"burst":5,"per_minute":20},"endpoint":"POST /items","retry_after_seconds":3,"request_id":"req-8f2c"}
```

Allowed responses also include a `RateLimit` header for the tightest bucket. In that header, `r` is the number of requests left and `t` is the number of seconds until the bucket is full. On a 429, `t` is the wait for the next token, which is the same value as `Retry-After`. This lets a well-behaved sync job slow down before it is rejected.

The header follows the syntax of `draft-ietf-httpapi-ratelimit-headers`. `RateLimit-Policy` is omitted because its quota-per-window format does not map cleanly to "rate plus burst." Shadow mode sends no header, so a trial cannot slow clients down.

## Known gaps

- **Unauthenticated traffic is never limited.** A missing or bad API key gets a 401 before the limiter runs. That keeps junk keys out of memory, but it does not slow a flood of bad requests. Fix: add a small per-IP bucket on 401s, or do this at the load balancer. Only trust an IP from a trusted proxy header.
- **CORS preflights skip auth and the limiter.** Browsers send `OPTIONS` preflights without credentials, so they cannot carry the API key. Spring answers them without running a controller. Treat a preflight flood like a 401 flood: handle it at the edge.
- **405, 406, and 415 responses skip auth and the limiter.** Spring can reject the request while choosing the handler, before any interceptor runs. These responses are cheap, but they are neither authenticated nor counted. An edge limit or servlet filter would cover them.
- **Every 429 is logged.** A client that ignores `Retry-After` writes one WARN line per rejected request. This keeps each 429's `request_id` findable, but it can create high log volume. In production, sample or aggregate per org and keep a counter.
- **At the memory cap, an untracked endpoint is limited only by the org total.** Failing open avoids locking out new customers, and `limiter_full` pages someone.
- **There is one lock for all orgs.** A normal check is only a few map operations, but a sweep of 100,000 buckets takes a few milliseconds, and every request, from every org, waits for it. Next step: use a lock per org and an incremental sweep.
- **There are no per-user limits inside an org.** One user's script can still use the whole org allowance.

## Moving to shared state, not built

The assignment rules out Redis, so this section is a plan, not implemented code. This is the direction B1 needs once a shared store is allowed.

- **Use one atomic script per request.** A Lua script should check and take from all buckets for the request. Keys need a Redis Cluster hash tag, such as `rl:{acme}:total` and `rl:{acme}:POST /items`, so they land on one shard. Without that, the script fails with `CROSSSLOT`.
- **Use expiry instead of sweeps.** Each key gets a `PEXPIRE` of roughly its refill time, so idle orgs cost nothing.
- **Plan for hot keys.** During an incident, the noisy org becomes one hot key on one shard. A local pre-check, or leasing a few tokens per instance, keeps most of those requests off the network.
- **Use Redis time.** Redis `TIME` gives every instance one clock. It is wall-clock time, so a jump can shorten or stretch one refill; that is acceptable here.
- **Expect failover to partially reset limits.** With asynchronous replication, a failover can lose recent decrements, similar to a small restart.
- **If Redis is down, fall back instead of taking the API down.** Time out at about 20 ms behind a circuit breaker, fall back to this in-memory limiter with each limit divided by the instance count, with a floor of 1, and page someone. Fail fully open only if that fallback also fails.

## Code map

| File | What it does |
| --- | --- |
| `ratelimit.yml` | All limits, org tiers, route overrides, shadow mode, and memory cap. |
| `ratelimit/TokenBucketLimiter.java` | Buckets, all-or-nothing checks, and eviction. No Spring. |
| `ratelimit/RateLimitPolicy.java` | Turns config into the list of buckets for one request; performs startup validation. |
| `ratelimit/web/RateLimitInterceptor.java` | The middleware: API key to org, 401s, checks, headers, 429 bodies, and logs. |
| `ratelimit/web/RouteOverrideCheck.java` | Stops startup if a route override matches no controller. |
| `ratelimit/web/RateLimitWebConfig.java` | Registers the interceptor for every route. |
| `FailureModesTest.java` | Tests that pass because a limitation exists. |
| `ShippedConfigTest.java` | Loads the real `ratelimit.yml`, so a typo in it fails the build. |

Controllers contain no rate-limiting code. A new endpoint automatically gets the read or write defaults.

---

## Section A: Architecture and trade-offs

### Algorithm: token bucket

Each bucket holds up to `burst` tokens and refills continuously at `per-minute`. Every request spends one token. Refill is computed from monotonic time, `System.nanoTime`, when a request arrives. There are no timers.

The incident was a sustained overload, not a spike. Any useful algorithm must cap sustained rate. The token bucket also separates sustained rate from burst size: after a sync job spends its burst, it is held to its configured rate, while someone clicking through the UI still gets a short burst. It gives an exact `Retry-After`, uses O(1) memory per bucket, and avoids a fixed window's double burst at the boundary.

### Where token buckets are not enough

- **They count requests, not cost.** If the incident was caused by expensive requests, such as large pages or fan-out, a request limit might not have stopped it. The real fix is per-org concurrency caps or cost-weighted tokens.
- **"100/min" is a refill rate, not a strict ceiling for every 60-second window.** A full bucket plus one minute of refill allows `burst + 100`.
- **State lives in one process.** See B1.

### How org and endpoint limits interact

Every request must pass two buckets:

1. **`org-total`** counts every request from the org to any endpoint. Its size comes from the tier: 100/min for standard, 500/min for premium.
2. **The endpoint bucket** is keyed by org, method, and route template, for example `GET /items/{id}`, not `/items/123`. Keying by raw path would let a client create unlimited buckets. Reads, meaning GET and HEAD, use the tier's read limit, which is below the total. Everything else uses the stricter write limit. `route-overrides` sets a limit for one expensive endpoint, such as `POST /exports`.

Both buckets are checked under one lock. A token is taken from both buckets or from neither, so rejected requests, including retry storms, spend no tokens. If more than one bucket rejects, the response names the bucket with the longest wait, because retrying when the first bucket refills would just hit the next one.

All API keys for one org share buckets. One org cannot touch another org's buckets. The org comes from the API key, not from a client-supplied org header; otherwise a client could rotate org IDs to get fresh buckets. The key-to-org map in config stands in for real auth.

### What I would change with more time

- Use a shared store such as Redis, once infrastructure is allowed. See B1.
- Add a per-org concurrency limit, then cost-weighted tokens for heavy endpoints.
- Add metrics per limit and org, not just log lines.
- Add config hot reload. Today an edit needs a restart, which resets counters. Existing live buckets also keep the limit they were created with, so reload must update them.
- Set tier numbers from capacity measurements. The current 100 and 500 values come from the brief.

---

## Section B: Production readiness plan

### B1 — Failure Modes and Scaling Plan

#### 1. Process restart

Every bucket is lost. Each org starts again with a full burst. `scripts/demo.sh` prints `201 201 201 201 201 429 429` before and after a restart.

This is acceptable for routine deploys: a sync job is back at its limit within seconds, although a rolling deploy of N instances can hand out up to N extra bursts. It is not acceptable in a crash loop, where the noisy org gets a free burst every time.

#### 2. Multiple instances

Each instance has its own buckets, so N round-robin instances allow N times the limit. In the demo, 2 replicas allowed 10 requests against a burst of 5.

Dividing limits by N breaks under uneven balancing and autoscaling. Once infrastructure is allowed, use a shared store such as Redis. One atomic script per request should check and take from all buckets. If the store is down, fall back to this limiter with limits divided by N and page someone. A rate-limiter outage must not become an API outage. See [Moving to shared state, not built](#moving-to-shared-state-not-built).

#### 3. Memory growth

Keys come from authenticated orgs multiplied by route templates, so they are bounded. Fully refilled buckets are dropped for free. A bucket measured about 157 bytes, so the default cap of 100,000 is roughly 16 MB.

The design becomes dangerous if someone keys by IP, user ID, or raw path. Drawn-down buckets are never evicted, because that would reset an abuser. At the cap, existing buckets are still enforced. Missing buckets go untracked and log `limiter_full`, so new customers are not locked out.

Signals that should catch this first:

- a bucket-count gauge against the cap, alerting at 80% (to add; no metrics yet);
- an alert on `limiter_full`, which is logged at most every 5 seconds; and
- JVM heap use.

### B2 — AI reasoning question

In building this, I encountered a scenario where the AI assistant did give a plausible but incorrect answer.
The original implementation had the rate limiter failing closed when max buckets was exceeded, which was the 
opposite of what the requirements stated. I had an AI agent implement a fix for that, which it did and it also 
included a test for that scenario. However, the fix was incorrect because it allowed an org to bypass the rate limit 
if it had an empty total bucket and called an endpoint it had not used recently. I caught this bug by doing a code 
review of the AI agent's work. The output looked correct, and even came with a passing test, but the overall logic was 
wrong. To correct this, I had another AI agent write tests to reproduce the bug and fail
(`FailureModesTest.atTheCapExistingBucketsAreStillEnforcedForNewEndpoints` and 
`LimiterFullTest.aLimitedOrgCannotSlipThroughViaAnEndpointWithNoBucket`) and then I had the agent fix the bug and ensure
the new tests passed.

---

## Section C: AI usage log

I sent the same design brief to three models separately and asked for an opinionated recommendation on 11 questions. None of the models saw the others' answers. Claude Code then compared the three answers, wrote the code and tests, and later reviews checked the result. I treated every answer as a draft, and the tests check the claims that mattered.

### 1. Argon, Gemini via API

**Asked:** the design brief.

**Gave:**

- a token bucket with lazy refill;
- an evaluate-then-commit check across an org bucket and a read/write bucket;
- client identity from a trusted `X-Org-Id` header;
- a bounded LRU map for memory;
- `RateLimit-Limit`, `RateLimit-Remaining`, and `RateLimit-Reset` headers;
- failure modes as executable tests, including a test where flooding new keys evicts a tracked abuser; and
- Redis through one Lua script, with a timeout, circuit breaker, and fail-open behavior.

**Kept:** lazy refill, evaluate-then-commit, failure-mode tests, and the Redis/fail-open plan in B1.

**Rejected:**

- `X-Org-Id`. It can be spoofed, and rotating it gets fresh buckets.
- LRU eviction. Evicting a drawn-down bucket resets an abuser.
- The `RateLimit-Limit`, `RateLimit-Remaining`, and `RateLimit-Reset` header names. The current draft uses `RateLimit` and `RateLimit-Policy`.

**Changed:** its eviction test became `aKeyFloodFillsTheCapAndNewKeysGoUntracked`. Under the final design, a key flood cannot evict anyone. The test now shows the real cost: once the cap is full, new keys go through untracked.

### 2. Claude, separate subagent with no shared context

**Asked:** the same brief.

**Gave:**

- a token bucket stored as GCRA, which uses a single timestamp per key;
- the point that request count is not capacity, so this limit might not have stopped the incident;
- shadow mode;
- route templates rather than raw paths in keys;
- "binding limit = longest wait";
- strict startup validation;
- org identity from the authenticated key; and
- LRU eviction that fails open, as a deliberate choice.

**Kept:** shadow mode, route-template keys, longest-wait binding limit, startup validation, key-based identity, and the "requests do not equal cost" caveat, now the first trade-off in Section A.

**Rejected:**

- GCRA storage. It behaves the same, but tokens plus a timestamp are easier to read at 11pm.
- Fail-open eviction, for the reason above.

**Changed:** its single read/write bucket per org became one bucket per endpoint, following Codex.

### 3. Codex, OpenAI Codex CLI, read-only

**Asked:** the same brief.

**Gave:**

- never evict a drawn-down bucket; only evict fully refilled buckets;
- 503 for new keys when the cap is full;
- one bucket per (org, method, route template);
- Flask, because its hook exposes the matched route template;
- the warning that the GIL does not make check-and-take atomic;
- `Cache-Control: no-store` and `request_id` on 429s;
- leaving out `RateLimit-Policy`;
- "add Redis" is not a migration design; and
- tier numbers need capacity measurements; run the restart and replica demos as real subprocesses.

**Kept:** nearly all of it. The eviction policy, per-endpoint buckets, response details, and subprocess demos in `scripts/demo.sh` come from Codex. So does the explicit Redis-down policy in B1.

**Rejected:** Flask. We chose Spring Boot, and `HandlerInterceptor` gets the same matched route template from `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`. Java threads need the lock for the same reason the GIL does not help.

**Changed:** Codex's 503 for new keys at the cap was reversed after a separate Claude review flagged that it contradicted B1's never-an-outage principle. I agreed and changed it: a full limiter now lets new orgs through and logs `limiter_full`. Codex also wanted unknown HTTP methods denied. Spring already rejects methods no route handles with a 405 before the middleware runs, so those requests are neither authenticated nor counted. Any method other than GET and HEAD on a matched route gets the stricter write limit.

### 4. Claude, separate code review in two rounds

**Asked:** review the finished code and this README draft.

**Gave:**

- **Round 1:** the 503 at the cap contradicted B1's never-an-outage principle; the 10,000 default could fill in normal traffic; at the cap, every request needing a new bucket ran a full sweep under the single lock; the `rate_limited` log line lacked the `request_id` the client is given; unauthenticated traffic is never limited; CORS preflights got 401s; one test comment was confusing; and there was a hot-reload caveat.
- **Round 2:** a real bug in Claude Code's fail-open change; `limiter_full` logged one ERROR per request during a flood; and there were two inaccuracies in this log, including a line that credited its finding to "a human review."

**Kept:** all of it. Code fixes went in as separate commits with tests. The unauthenticated-traffic gap and hot-reload caveat are documented, not fixed. For the cap, I chose fail-open with a 100,000 default, sized from a measured 157 bytes per bucket, rather than keeping the 503.

**Changed:** the request ID is generated only on paths that log it, so allowed requests do not pay for a UUID. Before skipping preflights in both interceptors, I checked Spring's source to confirm that registered interceptors stay on preflight requests.

### 5. Four-way review: Claude, Argon via agy, Codex, and Cursor

**Asked:** each tool, separately and read-only, to review everything for bugs and for claims the code does not back up. Then, in a second pass, to find simplifications.

**Gave:**

- **Found by several:** async handlers charged twice because Spring calls the interceptor again when they complete; config typos accepted silently; a request needing two new buckets could not use one free slot at the cap; 405s bypass the middleware; the Redis plan had gaps; and unsafe `X-Request-Id` values could reach logs.
- **Found by one:** HEAD got its own read bucket; read limits equaled the total; shadow mode still sent headers; `demo.sh` had a restart race; and the shipped config was not tested.
- **Simplifications:** all four suggested merging the two interceptors and cutting duplicated test setup.

**Kept:** every bug fix above, as separate commits. Each code bug was reproduced by a failing test before it was fixed. The `demo.sh` race was fixed from reading the script, and the read limits are a config change. I also kept the interceptor merge, flattened Spring config, shared test setup, and shorter comments.

**Rejected:**

- Cursor's suggestions to drop the `RateLimit` header on success and to fail closed at the cap. The header is how a sync job slows down before a 429; failing closed brings back the outage interaction 4 removed.
- Codex's claim that the CORS test passes without its fix. Without the fix, it fails with a 401.
- Cursor's claim that the 157-byte figure counts only the bucket object. It was measured with 200,000 realistic keys in the real map.
- Throttling the `rate_limited` log line. It would stop support from finding a customer's 429 by `request_id`, so it is listed under [Known gaps](#known-gaps) instead.

**Changed:** `OPTIONS` was meant to share GET's bucket too. Spring answers it with its own handler, which has no route, so like a 404 it is charged to the org total only.

### 6. Documentation readability pass: Custom skill using Codex and Argon 

Frontier models tend to produce long, dense text which are not well-suited for "someone reading at 11pm who can't ask follow-up questions."
I used a custom skill to rewrite this README for clarity, scannability, and conciseness. It did not change any code or tests. Section B2 was left as a placeholder for me to answer without AI assistance.


### What the AI got wrong during the build

1. Claude Code wrote one test assertion with the wrong arithmetic: it expected 0 tokens left where 2 was correct.
2. It wrote a config import path that worked in tests but failed from the packaged jar.
3. Its first fail-open change let a request skip all limits when any of its buckets was missing at the cap. That meant an org with an empty total could get through by calling an endpoint it had not used recently. The round-2 review caught it. A test reproduced it first, expecting `RATE_LIMITED` but getting `LIMITER_FULL`, and the fix still enforces existing buckets at the cap.
4. When it lowered the per-endpoint read limits, it left initech's write override equal to the new read limit, so writes were no longer stricter. The shipped-config test it added in the same review caught it.

The tests and `scripts/demo.sh` caught the first, second, and fourth problems; review caught the third. All four are fixed.
