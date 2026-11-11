package io.astra.pluginhost.jvm;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds plugin jars in memory from Java source, compiled against the test classpath (which has astra-spi). */
public final class TestPlugins {

    private TestPlugins() { }

    /** A carrier plugin {@code test.carrier.Carrier} whose {@code book} body is {@code body}. */
    public static byte[] carrier(String version, String network, String timeout, int maxConcurrency, String body) {
        String source = """
                package test.carrier;
                import io.astra.spi.app.*;
                import io.astra.spi.integration.*;
                public class Carrier implements CarrierAdapter, AstraPlugin {
                    private PluginContext ctx;
                    public void start(PluginContext ctx) { this.ctx = ctx; }
                    public Booking book(Shipment s) {
                        try { %s } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
                    }
                }
                """.formatted(body);
        String manifest = """
                id: test.carrier
                version: %s
                tier: isolated-jvm
                entrypoint: test.carrier.Carrier
                spi: io.astra.spi.integration.CarrierAdapter
                capabilities: { network: [ %s ] }
                runtime: { timeout: %s, max-concurrency: %d, circuit-breaker: { failure-rate: 0.5, window: 30s } }
                """.formatted(version, network, timeout, maxConcurrency);
        return jar(manifest, Map.of("test.carrier.Carrier", source));
    }

    /** A carrier whose static initializer sets system property {@code propKey} to "ran": a tripwire for code running. */
    public static byte[] carrierWithStaticInit(String version, String propKey) {
        String source = """
                package test.carrier;
                import io.astra.spi.app.*;
                import io.astra.spi.integration.*;
                public class Carrier implements CarrierAdapter, AstraPlugin {
                    static { System.setProperty("%s", "ran"); }
                    public void start(PluginContext ctx) { }
                    public Booking book(Shipment s) { return new Booking("fastship", s.waybill(), 1000, s.service(), ""); }
                }
                """.formatted(propKey);
        String manifest = """
                id: test.carrier
                version: %s
                tier: isolated-jvm
                entrypoint: test.carrier.Carrier
                spi: io.astra.spi.integration.CarrierAdapter
                capabilities: { network: [ ] }
                runtime: { timeout: 300ms, max-concurrency: 4 }
                """.formatted(version);
        return jar(manifest, Map.of("test.carrier.Carrier", source));
    }

    public static byte[] jar(String manifest, Map<String, String> sources) {
        Map<String, byte[]> classes = compile(sources);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(PluginManifest.PATH));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            for (var e : classes.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey().replace('.', '/') + ".class"));
                jar.write(e.getValue());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        Map<String, ByteArrayOutputStream> out = new LinkedHashMap<>();
        JavaFileManager files = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return out.computeIfAbsent(className, k -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, src) -> units.add(new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) { return src; }
        }));
        StringWriter errors = new StringWriter();
        boolean ok = compiler.getTask(errors, files, null,
                List.of("-classpath", System.getProperty("java.class.path"), "--release", "25"), null, units).call();
        if (!ok) throw new IllegalStateException("plugin source does not compile:\n" + errors);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        out.forEach((k, v) -> classes.put(k, v.toByteArray()));
        return classes;
    }
}
