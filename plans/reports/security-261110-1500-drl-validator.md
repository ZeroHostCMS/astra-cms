# Security review — DRL "price rules as data" validator boundary

Date: 2026-11-10 (Asia/Saigon)
Scope: read-only analysis + throwaway JUnit probe of the allowlist that guards tenant-authored
Drools DRL before it is compiled and run inside the core JVM.
Target files:
- `astra-kernel/src/main/java/io/astra/kernel/rules/RuleValidator.java`
- `astra-kernel/src/main/java/io/astra/kernel/rules/RuleEngine.java`
- `astra-spi/src/main/java/io/astra/spi/pricing/QuoteRequest.java`, `QuoteLines.java`

Verdict: **One critical bypass found — remote code execution.** A DRL text passes
`RuleValidator.check`, compiles, and executes arbitrary Java (file write and `Runtime.exec`
confirmed) inside the core JVM. A second, lower-severity DoS is also confirmed.

---

## CRITICAL — char-literal `'"'` desynchronizes the string blanker → arbitrary Java (RCE)

### Root cause
`RuleValidator.blankStringsAndComments` (lines 104–127) blanks the contents of double-quoted
strings and comments before the per-word allowlist runs. Its scanner has **no concept of a Java
char literal**. A single-quoted char literal whose content is a double-quote — `'"'` — is three
characters `'`, `"`, `'`. The blanker hits the `else` branch for the two `'`, and treats the inner
`"` as the **start of a string literal**, blanking everything until the next `"` it finds.

The Drools consequence (`then`) compiles in the default **java** dialect, where `'"'` is simply the
char value `0x22`. So the validator and the Drools compiler disagree about where strings begin and
end. Place two `'"'` char literals around a payload and the validator blanks the whole payload (sees
only `lines.floor('    …blanked…    ');`) while Drools compiles and runs the real code between them.

The only constraint on the payload is that it must contain no `"` character (so the blanked region
stays correctly paired). That is not a real limitation: any string the payload needs is built from
`new String(new char[]{..})`, which contains no `"`.

### Exact DRL (confirmed: writes an arbitrary file)
```drl
package t
import io.astra.spi.pricing.QuoteRequest
global io.astra.spi.pricing.QuoteLines lines
rule "r"
when
    QuoteRequest()
then
    lines.floor('"');new java.io.File(new String(new char[]{47,116,109,112,47,112,119,110})).createNewFile();lines.floor('"');
end
```
`new char[]{47,116,109,112,47,112,119,110}` is the ASCII for `/tmp/pwn`. Result: `RuleValidator.check`
returns `1 rule` (PASS), and running the rule creates `/tmp/pwn`.

### Confirmed full RCE (`Runtime.exec`)
The same pattern with a char-array-built command ran an external process (`/bin/sh -c "touch …"`)
and the sentinel file appeared:
```
then
    lines.floor('"');java.lang.Runtime.getRuntime().exec(new String[]{new String(new char[]{47,98,105,110,47,115,104}),new String(new char[]{45,99}),new String(new char[]{..touch <path>..})}).waitFor();lines.floor('"');
```

### Probe results (throwaway `DrlBypassProbeTest`, now deleted)
```
[VALIDATOR BLOCK] direct System.exit                     -> 'System'/'exit' not allowed   (baseline: works)
[VALIDATOR PASS ] char-literal hides System.currentTimeMillis -> 1 rule(s)
[RUNTIME ] floor after bypass = 1794297285744            (System.currentTimeMillis executed)
[VALIDATOR PASS ] char-literal hides File.createNewFile  -> 1 rule(s)
[RUNTIME ] arbitrary file created? true                  (/tmp/astra_drl_pwned_…)
[VALIDATOR PASS ] char-literal hides Runtime.exec        -> 1 rule(s)
[RUNTIME ] Runtime.exec ran (sentinel exists)? true      (/tmp/astra_drl_exec_…)
```

### Blast radius
Runs in the core JVM with no sandbox. The attacker is a tenant **business analyst** who can save a
PricingRuleset (the documented self-service flow in `docs/pricing-rules.md`). The payload executes
at **save/validate time too**, not only in production: `RuleEngine.compiled()` calls a `run(... WARMUP ...)`
warm-up immediately after compile, so merely saving a ruleset that passes validation runs the code.
This is full RCE: file read/write, process execution, network, reading secrets/env, reflection — all
reachable. It also trivially defeats every other denylist item the validator was designed to stop
(`System`, `Runtime`, `new`, FQNs, reflection), because those tokens live in the blanked region.

### Fix (defence in depth — do all three)
1. **Teach the blanker about char literals.** In `blankStringsAndComments`, add a branch for `'`
   that blanks a char literal the same way as a string (consume `'`, then contents until the
   closing `'`, honoring that a `'` cannot legally contain an unescaped `"` acting as a delimiter).
   This closes the specific desync. Add a test with `then lines.floor('"'); <payload> lines.floor('"');`.
2. **Do not trust a hand-written lexer as the security boundary.** The real defence is to stop
   running untrusted code in the core JVM at all. Prefer one of:
   - Parse the DRL with Drools' own parser and walk the AST, allowlisting node types / method
     targets — not a regex over blanked text; or
   - Execute rule sets in an out-of-process / strongly-sandboxed worker (separate JVM with a
     restrictive SecurityManager replacement such as a seccomp/container boundary, no filesystem,
     no network, no process spawn), so a validator miss is not game-over.
3. **Pin the dialect and forbid raw Java statements.** Even with (1), the java dialect lets
   consequences call any visible class. Consider restricting consequences to a whitelisted
   expression grammar or an MVEL dialect with a locked-down property/ method resolver, rather than
   relying on token blanking.

---

## MEDIUM — CPU-exhaustion DoS: the deadline cannot stop a tight loop

### Finding
A loop hidden by the same char-literal trick passes the validator:
```
then
    lines.floor('"');long $i=0; while($i>=0){ $i=$i+1; } lines.floor('"');
end
```
(`$`-prefixed identifiers are always allowed — `RuleValidator.checkWords`, line 97 — and `while`,
`new`, etc. sit in the blanked region.)

`RuleEngine.run` enforces the deadline with `Future.cancel(true)` (lines 151–154), i.e. a thread
**interrupt**. A tight arithmetic loop never checks `Thread.interrupted()`, so the interrupt is
ignored and the virtual thread keeps spinning after the caller has already been unblocked with a
timeout exception. The probe confirmed the warm-up path hit its 5000 ms deadline and returned, while
the loop thread continued — orphaned, consuming a CPU core until JVM exit. Repeated saves/quotes
accumulate orphaned spinners and exhaust CPU. (Note: because the warm-up fires at compile time with
a 5000 ms deadline, a single save already burns ~5 s before the engine gives up.)

### Fix
Interrupt-based cancellation is advisory and insufficient against CPU-bound code. Run rule execution
in a resource-bounded worker that can be hard-killed (separate process, or a platform thread you can
forcibly stop via process/cgroup limits), and/or inject interrupt checks is not possible for
attacker code — so process-level CPU/time limits are the only reliable control. The char-literal fix
above also removes the ability to write a raw `while`/`for` loop in the first place, which is the
practical mitigation here.

---

## Techniques tried and BLOCKED (boundary holds for these)

| Technique | Blocked by | Note |
|---|---|---|
| `System.exit(0)`, `Runtime...exec`, `Class.forName`, `new java.io.File(..)`, `drools.halt()`, `kcontext..`, `insert(..)`, `Thread.sleep` (written plainly) | Validator (word allowlist) | tokens not on allowlist |
| Fully-qualified names `java.lang.System.exit` | Validator | `IDENT` splits on `.`; each segment (`java`,`lang`) checked |
| `eval(..)`, `from`, `accumulate`, `function`, `query`, `declare`, `global X`, extra `import` | Validator | keyword/word or line-shape checks |
| `dialect "mvel"` on the rule line | Validator | `dialect`, `mvel` not allowed (rule-line words are checked) |
| `matches` regex (ReDoS) | Validator | deliberately removed from KEYWORDS |
| Backslash / `"` unicode-escape smuggling | Validator | `drl.indexOf('\\') >= 0` refuses any backslash |
| `$System.exit(0)` ( `$`-ident is allowed) | Validator | `$System` passes, but bare `exit` token still caught; and Drools has no `$System` binding → compile error anyway |
| Single-quoted *multi-char* `'System'` | Validator | single-quote content is **not** blanked, so `System`/`length` inside are word-checked and caught — only the single `"`-inside-`'…'` case desyncs |
| `/* " */ System.exit(0) /* " */` (quote inside block comment) | Validator | block-comment blanking runs and consumes the `"`; `System`/`exit` outside the comment are caught |
| Code after `end` (e.g. `end\nSystem.exit(0);`) | Validator | lines outside a rule must match package/import/global/rule-start; `System` also caught, and "last rule has no end" |
| Duplicate `lines.add(code,...)` | Runtime (`QuoteLines.add` throws → `ScriptException "trap"`) | existing behavior, not a bypass |

Observations that are **not** exploitable on their own:
- `$`-prefixed identifiers bypass the allowlist (line 97), but an unbound `$Foo` is a Drools compile
  error unless it resolves to something; it only becomes dangerous combined with the char-literal
  blanking (which already supplies full RCE without needing `$`).
- `blankStringsAndComments` handles `//`, `/* */`, and multi-line double-quoted strings correctly
  (newlines preserved, line numbers kept). The single defect is the missing char-literal case.

---

## Summary of fixes (priority order)
1. **(Critical)** Make `blankStringsAndComments` recognize `'…'` char literals so an inner `"`
   cannot start a phantom string. Add regression tests for `'"'`-bracketed payloads.
2. **(Critical, architectural)** Stop treating a regex/blanking lexer as the trust boundary for
   code that runs in the core JVM. Move rule execution into an out-of-process / sandboxed worker
   with no filesystem, network, or process-spawn, and hard CPU/time limits; and/or validate against
   the real Drools AST instead of blanked text.
3. **(Medium)** Replace interrupt-based deadline with process/cgroup-enforced CPU limits for a
   reliable DoS control; the char-literal fix removes raw loops as a prerequisite.

Throwaway probe `DrlBypassProbeTest.java` was added under
`astra-kernel/src/test/java/io/astra/kernel/rules/` to confirm these results and has been **deleted**.
No source files were modified.
