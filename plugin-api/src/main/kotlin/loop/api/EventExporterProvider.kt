package loop.api

/**
 * An event exporter plugin (m4-42): it sends the events Loop records to another system, such as an OpenTelemetry
 * backend. Providers are found with Java's `ServiceLoader`, on the classpath and in each JAR of `<LOOP_HOME>/plugins/`,
 * like runner and tracker plugins, so a module registers its exporter with a
 * `META-INF/services/loop.api.EventExporterProvider` resource naming its provider class, which needs a public no
 * argument constructor. Two providers of one [key] fail startup.
 *
 * An exporter is opt in: it runs only when `WORKFLOW.md` has its section, `exporters.<key>`. The host wraps it in the
 * run's [Redactor] with [RedactingEventSink] before it sees an event, so it never gets a secret the store would not
 * keep.
 */
public interface EventExporterProvider {
    /** The key of the provider's own section under `exporters`, for example `otel`. */
    public val key: String

    /**
     * The keys of the section `exporters.<key>`, checked before [validate] reads them: a missing required key, a wrong
     * type or a value out of its limits fails preflight, an unknown key gives a startup warning.
     */
    public val profile: PluginProfile

    /**
     * Reads [config], the section `exporters.<key>` as written (string, list, map or null values), with [env], the host
     * env, and throws [ExporterConfigException] when it is invalid. Loop calls it in the preflight of startup and of
     * every tick, so a bad section fails before any attempt.
     */
    public fun validate(
        config: Map<String, Any?>,
        env: Map<String, String>,
    )

    /**
     * The names of the env variables whose values [config] sends somewhere, such as the `$NAME` of a header. The run's
     * redactor learns their values, so they are redacted wherever Loop writes. None by default.
     */
    public fun secretEnvNames(config: Map<String, Any?>): Set<String> = emptySet()

    /** Builds the exporter from [config], read like [validate], and the host [env]. Loop closes it when the run ends. */
    public fun create(
        config: Map<String, Any?>,
        env: Map<String, String>,
    ): EventExporter
}

/**
 * An exporter built by an [EventExporterProvider]: an [EventSink] that returns a failed [Result] when it cannot export,
 * and never throws, so a broken exporter never stops the run. [close] sends what it still holds and frees its
 * resources; Loop calls it once, when the run ends.
 */
public interface EventExporter :
    EventSink,
    AutoCloseable

/** An invalid exporter section; the message names the key, `exporters.<key>.<name>`, never a secret value. */
public class ExporterConfigException(
    message: String,
) : IllegalArgumentException(message)
