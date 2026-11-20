package loop.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Runner plugin JARs for the m3-04 tests and tracker plugin JARs for the m3-05 tests, built at test time. The provider is compiled from Java source with the
 * JDK's compiler, so its class exists only in the JAR: the plugin class loader, not the test classpath, defines it.
 */
object PluginJars {
    /**
     * Compiles `loop.testplugin.[className]`, a provider of runner kind [kind], in [work] and writes it to
     * `[pluginsDir]/[className].jar` with its `META-INF/services` entry. Its profile declares `script`, required, and
     * the runner it builds is the classpath's command runner running that script. Without [withProfile] it is a
     * provider of the m3-04 shape, which declares no profile (bl-104). Returns the JAR.
     */
    fun build(
        work: Path,
        pluginsDir: Path,
        className: String,
        kind: String,
        withProfile: Boolean = true,
    ): Path =
        compile(
            work,
            pluginsDir,
            className,
            "loop.api.RunnerProvider",
            """
            package loop.testplugin;

            import java.nio.file.Path;
            import java.util.List;
            import java.util.Map;
            import java.util.ServiceLoader;
            import java.util.Set;
            import loop.api.AgentEnvPolicy;
            import loop.api.AgentRunner;
            import loop.api.PluginProfile;
            import loop.api.ProfileKey;
            import loop.api.ProfileType;
            import loop.api.RunnerConfigException;
            import loop.api.RunnerProvider;

            public final class $className implements RunnerProvider {
                @Override
                public String getKind() {
                    return "$kind";
                }

                ${if (withProfile) profileMethod else ""}

                @Override
                public void validate(Map<String, ?> config) {
                    if (!(config.get("script") instanceof String)) {
                        throw new RunnerConfigException("runners.$kind.script is required");
                    }
                }

                @Override
                public AgentRunner create(Map<String, ?> config, AgentEnvPolicy env, Path holdoutDir) {
                    validate(config);
                    for (RunnerProvider provider : ServiceLoader.load(RunnerProvider.class, RunnerProvider.class.getClassLoader())) {
                        if (provider.getKind().equals("command") && provider != this) {
                            return provider.create(Map.of("command", config.get("script")), env, holdoutDir);
                        }
                    }
                    throw new IllegalStateException("no command runner on the classpath");
                }
            }
            """.trimIndent(),
        )

    /**
     * m3-05: compiles `loop.testplugin.[className]`, a provider of tracker kind [kind], in [work] and writes it to
     * `[pluginsDir]/[className].jar` with its `META-INF/services` entry. Its profile is the classpath `files`
     * profile, its states the `files` states, and the tracker it builds is the classpath's `files` tracker.
     * Returns the JAR.
     */
    fun buildTracker(
        work: Path,
        pluginsDir: Path,
        className: String,
        kind: String,
    ): Path =
        compile(
            work,
            pluginsDir,
            className,
            "loop.api.TrackerProvider",
            """
            package loop.testplugin;

            import java.util.List;
            import java.util.Map;
            import java.util.ServiceLoader;
            import loop.api.PluginProfile;
            import loop.api.TrackerAdapter;
            import loop.api.TrackerContext;
            import loop.api.TrackerProvider;

            public final class $className implements TrackerProvider {
                private TrackerProvider files() {
                    for (TrackerProvider provider : ServiceLoader.load(TrackerProvider.class, TrackerProvider.class.getClassLoader())) {
                        if (provider.getKind().equals("files")) return provider;
                    }
                    throw new IllegalStateException("no files tracker on the classpath");
                }

                @Override public String getKind() { return "$kind"; }
                @Override public PluginProfile getProfile() { return files().getProfile(); }
                @Override public List<String> getDefaultActiveStates() { return files().getDefaultActiveStates(); }
                @Override public List<String> getDefaultTerminalStates() { return files().getDefaultTerminalStates(); }
                @Override public List<String> getDefinedStates() { return files().getDefinedStates(); }
                @Override public Object parse(Map<String, ?> provider, Map<String, String> env) { return null; }
                @Override public TrackerAdapter create(Object settings, TrackerContext context) { return files().create(settings, context); }
            }
            """.trimIndent(),
        )

    private val profileMethod =
        """
        @Override
        public PluginProfile getProfile() {
            return new PluginProfile(List.of(new ProfileKey("script", ProfileType.STRING, true, null)), Set.of(), Map.of());
        }
        """.trimIndent()

    private fun compile(
        work: Path,
        pluginsDir: Path,
        className: String,
        service: String,
        text: String,
    ): Path {
        val sources = work.resolve("src/loop/testplugin").createDirectories()
        val classes = work.resolve("classes").createDirectories()
        val source = sources.resolve("$className.java")
        source.writeText(text)
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "the test JVM has no Java compiler")
        val exit =
            compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                source.toString(),
            )
        assertEquals(0, exit, "javac failed on $source")
        val jar = pluginsDir.resolve("$className.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/$service"))
            out.write("loop.testplugin.$className\n".toByteArray())
            out.closeEntry()
            val entry = "loop/testplugin/$className.class"
            out.putNextEntry(JarEntry(entry))
            out.write(Files.readAllBytes(classes.resolve(entry)))
            out.closeEntry()
        }
        return jar
    }

    /**
     * Writes `[pluginsDir]/[name].jar` holding only a `META-INF/services` entry that names `loop.testplugin.[missing]`,
     * a class the JAR does not hold, so its provider cannot load. Returns the JAR.
     */
    fun buildUnloadable(
        pluginsDir: Path,
        name: String,
        missing: String,
    ): Path {
        val jar = pluginsDir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/loop.api.RunnerProvider"))
            out.write("loop.testplugin.$missing\n".toByteArray())
            out.closeEntry()
        }
        return jar
    }
}
