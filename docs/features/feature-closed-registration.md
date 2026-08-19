---
id: feature-closed-registration
title: A tenant that admits only provisioned people
type: feature
status: active
tags: [identity, security, m4]
---

# Closed registration

## 1. Why

An external provider answers the question "who is this". It does **not** answer the question
"is this one allowed".

For a contour of customers the difference is invisible: the first sign-in *is* the registration,
and everyone who confirmed an email should be let in. For an internal contour — an `oauth2-proxy`
in front of monitoring, say — it is unacceptable: infrastructure sits behind the provider, and
"this person has a Google account" must not mean "may look at our metrics".

Before this change every tenant created a stranger automatically — that is, moving an internal
contour onto shildik would have opened monitoring to the whole internet. Found while working on
M-48, before the rollout.

## 2. How

A tenant gained a `registrationOpen` flag, **open** by default — otherwise introducing it would
have broken an existing contour silently.

* open: a person found neither by identity nor by a verified email is created;
* closed: the same case answers `access_denied`, and **no record is created**.

The flag is set explicitly, when the tenant is created:

```
shildik tenant create internal --closed
```

A flag rather than a list of allowed addresses: a list is one more place where the right to enter
is stored, and it drifts apart from who is actually provisioned. People in a closed tenant are
created by an administrator (`shildik user import` or `admin/users`), and that is the single
source of truth.

## 3. What it does not do

* **It does not tell rights apart inside a tenant.** Whoever is in, is in entirely; roles and
  scopes are a later milestone.
* **It does not lock out those who already exist.** Closing a tenant leaves provisioned people
  alone: the door is shut to new ones, not to everyone.
* **It does not replace `enabled`.** `enabled = false` forbids a particular person from signing
  in; `registrationOpen = false` forbids new ones from appearing. Different questions, hence two
  flags.

## 4. Acceptance

* **Given:** the tenant is closed, the provider confirmed the person, we do not have them.
* **Then:** `access_denied`, and storage still holds zero users — the second half matters more
  than the first, because a record created silently is the hole itself.
* **And:** a provisioned person signs in to a closed tenant as usual.
