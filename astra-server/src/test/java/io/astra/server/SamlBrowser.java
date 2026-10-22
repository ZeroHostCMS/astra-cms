package io.astra.server;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A minimal browser for the SAML broker flow: follows redirects, submits the auto-posting SAML forms and the
 * IdP login form, and keeps cookies per path (Keycloak scopes them per realm and marks them Secure even on
 * http://localhost, which {@link java.net.CookieManager} would refuse to send). Signs in with the Astra
 * Desk client (Authorization Code + PKCE) and {@code kc_idp_hint=acme-corp}, so Keycloak goes straight to
 * the enterprise IdP, and stops at the redirect back to the Desk.
 */
final class SamlBrowser {

    static final String REDIRECT_URI = "http://localhost:8080/desk/";

    /** Where the flow ended: an authorization code, or the page Keycloak answered with instead. */
    record Outcome(String code, String samlResponseXml, int status, String body) { }

    private record Cookie(String name, String path, String value) { }

    private static final Pattern FORM = Pattern.compile("<form\\b[^>]*\\baction=\"([^\"]*)\"[^>]*>(.*?)</form>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("\\b(name|value)=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private final String keycloak;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<Cookie> cookies = new ArrayList<>();
    private final String verifier = randomVerifier();

    SamlBrowser(String keycloakBase) {
        this.keycloak = keycloakBase;
    }

    /** Signs {@code username} in at the IdP; {@code tamper} may rewrite the decoded SAML response first. */
    Outcome login(String username, String password, UnaryOperator<String> tamper) throws IOException, InterruptedException {
        String auth = keycloak + "/auth/realms/astra/protocol/openid-connect/auth?client_id=astra-desk&response_type=code"
                + "&scope=openid&redirect_uri=" + enc(REDIRECT_URI) + "&code_challenge=" + challenge()
                + "&code_challenge_method=S256&kc_idp_hint=acme-corp";
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(auth)).GET());
        String samlResponseXml = null;
        for (int step = 0; step < 20; step++) {
            if (r.statusCode() / 100 == 3) {
                URI next = r.uri().resolve(r.headers().firstValue("Location").orElseThrow());
                if (next.toString().startsWith(REDIRECT_URI)) {
                    return new Outcome(query(next).get("code"), samlResponseXml, r.statusCode(), r.body());
                }
                r = send(HttpRequest.newBuilder(next).GET());
                continue;
            }
            Matcher form = FORM.matcher(r.body());
            if (r.statusCode() != 200 || !form.find()) return new Outcome(null, samlResponseXml, r.statusCode(), r.body());
            URI action = r.uri().resolve(unescape(form.group(1)));
            Map<String, String> fields = inputs(form.group(2));
            if (fields.containsKey("SAMLResponse")) {
                samlResponseXml = new String(Base64.getMimeDecoder().decode(fields.get("SAMLResponse")), StandardCharsets.UTF_8);
                String posted = tamper.apply(samlResponseXml);
                fields.put("SAMLResponse", Base64.getEncoder().encodeToString(posted.getBytes(StandardCharsets.UTF_8)));
            } else if (r.body().contains("name=\"password\"")) {
                fields = new LinkedHashMap<>(Map.of("username", username, "password", password, "credentialId", ""));
            } else if (!fields.containsKey("SAMLRequest")) {
                return new Outcome(null, samlResponseXml, r.statusCode(), r.body());
            }
            r = send(HttpRequest.newBuilder(action).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(fields))));
        }
        throw new IllegalStateException("SAML flow did not finish within 20 steps");
    }

    /** Exchanges the authorization code (with the PKCE verifier) for an access token. */
    String accessToken(String code) throws IOException, InterruptedException {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(keycloak + "/auth/realms/astra/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code", "client_id", "astra-desk",
                        "code", code, "redirect_uri", REDIRECT_URI, "code_verifier", verifier)))));
        Matcher m = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body());
        if (r.statusCode() != 200 || !m.find()) throw new IllegalStateException("token exchange failed: " + r.body());
        return m.group(1);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpRequest probe = b.copy().build();
        String path = probe.uri().getPath();
        String cookie = cookies.stream().filter(c -> path.startsWith(c.path()))
                .map(c -> c.name() + "=" + c.value()).collect(Collectors.joining("; "));
        if (!cookie.isEmpty()) b.header("Cookie", cookie);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String set : r.headers().allValues("Set-Cookie")) store(set, path);
        return r;
    }

    private void store(String setCookie, String requestPath) {
        String[] parts = setCookie.split(";");
        int eq = parts[0].indexOf('=');
        String name = parts[0].substring(0, eq).trim();
        String value = parts[0].substring(eq + 1).trim();
        String path = requestPath.substring(0, requestPath.lastIndexOf('/') + 1);
        boolean expired = false;
        for (int i = 1; i < parts.length; i++) {
            String a = parts[i].trim();
            if (a.regionMatches(true, 0, "Path=", 0, 5)) path = a.substring(5);
            if (a.equalsIgnoreCase("Max-Age=0")) expired = true;
        }
        String p = path;
        cookies.removeIf(c -> c.name().equals(name) && c.path().equals(p));
        if (!expired && !value.isEmpty()) cookies.add(new Cookie(name, p, value));
    }

    private static Map<String, String> inputs(String formBody) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher input = INPUT.matcher(formBody);
        while (input.find()) {
            String name = null, value = "";
            Matcher a = ATTR.matcher(input.group());
            while (a.find()) {
                if (a.group(1).equalsIgnoreCase("name")) name = a.group(2);
                else value = unescape(a.group(2));
            }
            if (name != null) out.put(name, value);
        }
        return out;
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(Character.toString(cp)));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) return out;
        for (String kv : uri.getRawQuery().split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) out.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String randomVerifier() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String challenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
