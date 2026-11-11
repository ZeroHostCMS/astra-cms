package io.astra.pluginhost.jvm;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * One class loader per plugin version, defining the plugin's classes from the jar bytes in memory. Its parent shows
 * only the JDK (platform loader) and {@code io.astra.spi.*}: the plugin cannot see the kernel, Quarkus, Jackson or
 * another plugin, and two versions of the same plugin never share a class. Dropping every reference to the loader
 * (and so to the plugin's classes and instances) lets the JVM unload them.
 */
final class PluginClassLoader extends ClassLoader {

    static final String SPI_PACKAGE = "io.astra.spi.";

    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> entries;

    PluginClassLoader(String identity, Map<String, byte[]> entries, ClassLoader spiLoader) {
        super("plugin:" + identity, new SpiOnly(spiLoader));
        this.entries = entries;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                // The SPI and the JDK always come from the parent, even if the jar bundles copies of them.
                c = name.startsWith(SPI_PACKAGE) || !entries.containsKey(classEntry(name))
                        ? getParent().loadClass(name)
                        : findClass(name);
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = entries.get(classEntry(name));
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] bytes = entries.get(name);
        return bytes != null ? new ByteArrayInputStream(bytes) : getParent().getResourceAsStream(name);
    }

    private static String classEntry(String name) {
        return name.replace('.', '/') + ".class";
    }

    /** Parent of every plugin loader: JDK classes from the platform loader, {@code io.astra.spi.*} from the host. */
    private static final class SpiOnly extends ClassLoader {

        static {
            registerAsParallelCapable();
        }

        private final ClassLoader spiLoader;

        SpiOnly(ClassLoader spiLoader) {
            super("astra-spi-only", ClassLoader.getPlatformClassLoader());
            this.spiLoader = spiLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(SPI_PACKAGE)) return spiLoader.loadClass(name);
            return super.loadClass(name, resolve); // platform loader, then findClass, which refuses everything else
        }

        @Override
        public java.net.URL getResource(String name) {
            return ClassLoader.getPlatformClassLoader().getResource(name);
        }
    }
}
