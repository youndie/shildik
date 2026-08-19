# Security policy

These libraries sign and verify tokens, hash passwords and encrypt keys at rest. A bug here is
not a crash — it is somebody else holding a valid token. Please report suspected vulnerabilities
privately rather than in a public issue.

## Reporting

Open a [private security advisory](https://github.com/youndie/shildik/security/advisories/new),
or email **panic.xyb@gmail.com** with `shildik` in the subject.

Please include what you can: affected module and version, what an attacker gains, and the
smallest way to reproduce it. A failing test is worth more than a description.

You will get an acknowledgement within three days and an assessment within a week. This is a
small project maintained by one person — that is what can honestly be promised, and it is meant
literally.

## Scope

In scope: everything in this repository. Of particular interest — anything that makes a signature
verify when it should not, `alg` substitution, timing differences in comparisons, and the JWK
parsing in `crypto`.

Out of scope: the deployment of any service that uses these libraries; report those to whoever
runs it.

## Versions

Only the latest published version is fixed. There are no long-term support branches.
