package io.astra.kernel.rules;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.pricing.QuoteRequest;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The safety boundary of rules-as-data: rule sets run inside the core JVM, so before one is compiled every word in it
 * must be on an allowlist. A rule may match {@code QuoteRequest} on its properties, compare and do arithmetic, call
 * {@code Math} (ceil, floor, min, max, round, abs) and the {@code lines} global ({@code add}, {@code has},
 * {@code amount}, {@code total}, {@code lock}, {@code floor}); nothing else. That rules out by construction what a
 * denylist would chase one spelling at a time: {@code System}, {@code Runtime}, reflection, {@code new}, fully
 * qualified names, {@code eval}, {@code from}, DRL functions and queries, {@code insert}/{@code modify}, the
 * {@code drools} and {@code kcontext} handles, {@code .class}, another dialect, regular expressions (a backtracking
 * regex would not stop at the deadline), loops ({@code while}, {@code for}). Strings are blanked before words are
 * checked, so anything that could make this lexer and the compiler disagree on where a string ends is refused outright:
 * single quotes (Java char literals, DRL single-quoted strings), text blocks and backslashes,
 * so a Unicode escape cannot smuggle a quote into the generated Java.
 */
public final class RuleValidator {

    static final Set<String> IMPORTS = Set.of("io.astra.spi.pricing.QuoteRequest", "io.astra.spi.pricing.QuoteLines");

    private static final Set<String> KEYWORDS = Set.of("rule", "when", "then", "end", "salience", "no", "loop", "lock",
            "on", "active", "date", "effective", "expires", "enabled", "true", "false", "null", "and", "or", "not",
            "exists", "in", "long", "int", "double");   // no 'matches': a regex ignores the deadline
    private static final Set<String> LINES_METHODS = Set.of("add", "has", "amount", "total", "lock", "floor");
    private static final Set<String> MATH_METHODS = Set.of("ceil", "floor", "min", "max", "round", "abs");
    private static final Set<String> ALLOWED = allowed();

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.]+)\\s*;?$");
    private static final Pattern GLOBAL = Pattern.compile("^global\\s+(?:io\\.astra\\.spi\\.pricing\\.)?QuoteLines\\s+lines\\s*;?$");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+[a-z][\\w.]*\\s*;?$");
    private static final Pattern RULE_START = Pattern.compile("^rule\\s+\"[^\"]+\".*$");

    private RuleValidator() { }

    /** Refuses the rule set with every offending line; returns the number of rules. */
    public static int check(String drl) {
        List<String> errors = new ArrayList<>();
        if (drl.indexOf('\\') >= 0) errors.add("line " + lineOf(drl, drl.indexOf('\\')) + ": backslashes are not allowed in a rule set");
        if (drl.contains("\"\"\"")) errors.add("line " + lineOf(drl, drl.indexOf("\"\"\"")) + ": text blocks are not allowed in a rule set");
        String code = blankStringsAndComments(drl);
        // Strings are blanked before the words are checked, so the validator's idea of where a string starts must match
        // the compiler's exactly. A single quote (a Java char literal such as '"', or a DRL single-quoted string) would
        // let the two disagree and hide code inside what looks like a string: rule sets use double quotes only.
        int quote = code.indexOf('\'');
        if (quote >= 0) errors.add("line " + lineOf(code, quote) + ": single quotes are not allowed in a rule set; use double quotes");
        String[] lines = code.split("\n", -1);
        boolean inRule = false;
        boolean hasGlobal = false;
        int rules = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int n = i + 1;
            if (line.isEmpty()) continue;
            if (!inRule) {
                if (PACKAGE.matcher(line).matches()) continue;
                Matcher imp = IMPORT.matcher(line);
                if (imp.matches()) {
                    if (!IMPORTS.contains(imp.group(1))) errors.add("line " + n + ": import " + imp.group(1) + " is not allowed; only " + IMPORTS);
                    continue;
                }
                if (line.startsWith("global")) {
                    if (GLOBAL.matcher(line).matches()) hasGlobal = true;
                    else errors.add("line " + n + ": the only global is 'global QuoteLines lines'");
                    continue;
                }
                if (RULE_START.matcher(lines[i].trim()).matches()) {
                    inRule = true;
                    rules++;
                    checkWords(line, n, errors);   // attributes on the rule line too (strings are already blanked)
                    continue;
                }
                errors.add("line " + n + ": only package, import, global and rule ... end are allowed here: '" + clip(line) + "'");
                continue;
            }
            if (line.equals("end")) {
                inRule = false;
                continue;
            }
            checkWords(line, n, errors);
        }
        if (inRule) errors.add("the last rule has no 'end'");
        if (rules == 0) errors.add("the rule set has no rules");
        if (!hasGlobal) errors.add("declare 'global QuoteLines lines;' so rules can add price lines");
        if (!errors.isEmpty()) throw new DocValidationException(errors);
        return rules;
    }

    private static void checkWords(String line, int n, List<String> errors) {
        Matcher m = IDENT.matcher(line);
        while (m.find()) {
            String word = m.group();
            if (word.startsWith("$") || ALLOWED.contains(word)) continue;
            if (Character.isDigit(line.charAt(Math.max(0, m.start() - 1))) && word.matches("[LlDdFf]")) continue; // 1000L
            errors.add("line " + n + ": '" + word + "' is not allowed in a pricing rule");
        }
    }

    /** Replaces string contents and comments with spaces, keeping line numbers. */
    static String blankStringsAndComments(String drl) {
        StringBuilder out = new StringBuilder(drl.length());
        int i = 0;
        while (i < drl.length()) {
            char c = drl.charAt(i);
            if (c == '"') {
                out.append('"');
                i++;
                while (i < drl.length() && drl.charAt(i) != '"') out.append(drl.charAt(i++) == '\n' ? '\n' : ' ');
                if (i < drl.length()) out.append('"');
                i++;
            } else if (c == '/' && i + 1 < drl.length() && drl.charAt(i + 1) == '/') {
                while (i < drl.length() && drl.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < drl.length() && drl.charAt(i + 1) == '*') {
                int end = drl.indexOf("*/", i + 2);
                int stop = end < 0 ? drl.length() : end + 2;
                for (; i < stop; i++) out.append(drl.charAt(i) == '\n' ? '\n' : ' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Set<String> allowed() {
        Set<String> words = new HashSet<>(KEYWORDS);
        words.addAll(LINES_METHODS);
        words.addAll(MATH_METHODS);
        words.add("lines");
        words.add("Math");
        words.add("QuoteRequest");
        for (Method m : QuoteRequest.class.getMethods()) {
            if (m.getDeclaringClass() != QuoteRequest.class || Modifier.isStatic(m.getModifiers()) || m.getParameterCount() > 0) continue;
            String name = m.getName();
            if (name.startsWith("get") && name.length() > 3) words.add(Introspector.decapitalize(name.substring(3)));
            else if (name.startsWith("is") && name.length() > 2) words.add(Introspector.decapitalize(name.substring(2)));
        }
        return Set.copyOf(words);
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String clip(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
