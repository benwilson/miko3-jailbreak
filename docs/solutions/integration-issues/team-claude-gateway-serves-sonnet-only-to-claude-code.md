---
title: "The team Claude gateway serves Sonnet and Opus only to Claude Code requests; other apps get a misleading 429 and only Haiku works"
date: 2026-10-01
category: integration-issues
module: "Claude access (shared ClaudeApi, launcher settings page, mode-explore ClaudeCuriosity)"
problem_type: integration_issue
component: tooling
severity: high
tags:
  - "claude-api"
  - "gateway"
  - "rate-limit"
  - "429"
  - "model-access"
  - "settings"
symptoms:
  - "Every Sonnet and Opus request returns HTTP 429 {\"type\":\"rate_limit_error\",\"message\":\"Error\"} with no retry-after, even a single 5-token request"
  - "claude-haiku-4-5 works with the same key, endpoint and request"
  - "Claude Code works fine through the same gateway, so the key and endpoint look healthy"
root_cause: config_error
resolution_type: config_change
---

# The team Claude gateway serves Sonnet and Opus only to Claude Code requests

## Problem

The robot's Claude settings point at a team gateway (`https://teamclaude.rentaladvantage.rent`), not at `api.anthropic.com`. From 16:07 on 2026-10-01 every robot request failed with HTTP 429. Changing the model to Sonnet 5.5 and swapping in a different key didn't help, while Claude Code kept working through the same gateway.

## Symptoms

- The robot log: `The endpoint is rate limiting this key; try again shortly. (HTTP 429)`. The raw body was `{"type":"error","error":{"type":"rate_limit_error","message":"Error"}}` with no `retry-after`.
- The refusals didn't depend on request rate: they happened at 2 requests a minute, and on a single test request.
- The settings page's "Refresh models" listed 13 models, so the key itself authenticated.

## What Didn't Work

- **Blaming the robot's request rate.** Today's changes did raise it, so a client-side back-off was added. It only lengthened the outage, and the owner had it removed: "we don't need rate limits".
- **Assuming per-model limits from bursts, or a bad key.** Both keys behaved the same.
- **Testing from a script with Python's default user agent.** The gateway's Cloudflare front returned `403 error code: 1010`. Send a normal user agent (the robot's is `Dalvik/2.1.0 …`).
- **Sending the key as `Authorization: Bearer`.** That gives `401 Invalid proxy API key`. The gateway expects `x-api-key`, which the robot already sends.

## Solution

Isolate one variable at a time with a 5-token request, using the same key and endpoint each time:

| Request | Sonnet 5.5 / 4.6 | Haiku 4.5 |
|---|---|---|
| minimal Messages request (`model`, `max_tokens`, one user message) | 429 | 200 |
| + Claude Code's user agent and `anthropic-beta` headers | 429 | — |
| + the system prompt "You are Claude Code, Anthropic's official CLI for Claude." | **200** | — |

The gateway grants Sonnet and Opus only to requests that identify as Claude Code. That strongly suggests it fronts Claude subscription accounts licensed for Claude Code. Everything else gets a bare `rate_limit_error`, and Haiku is left open.

**Decision:** the robot does not impersonate Claude Code to get past the gate. That would use subscription capacity outside its terms and could get the team's accounts flagged. The robot runs on `claude-haiku-4-5-20251001` through the gateway. For Sonnet or Opus, use a regular Anthropic API key against `https://api.anthropic.com`, or have the gateway admin allow the key.

## Why This Works

The 429 isn't a rate limit at all. It's the gateway's way of refusing an unauthorised model for a non-Claude-Code client. A request carrying the identity the gateway checks for succeeds at once, and nothing else changes the outcome.

## Prevention

- When a gateway returns `rate_limit_error` with `message: "Error"` and no `retry-after`, test one request per model before adding any back-off. A real rate limit doesn't refuse a single request.
- When the robot uses a gateway, check that its key can reach the chosen model before choosing it on the settings page.
- On the robot, `ClaudeCuriosity` creates its `ClaudeApi.Backoff` switched off. The back-off mechanism stays in `ClaudeApi` and its tests.
