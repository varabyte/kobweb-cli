import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.clikt.output.ParameterFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.varabyte.kobweb.cli.common.DEFAULT_REPO
import com.varabyte.kobweb.cli.common.Globals
import com.varabyte.kobweb.cli.common.ProgramArgsKey
import com.varabyte.kobweb.cli.common.kotter.trySession
import com.varabyte.kobweb.cli.common.version.SemVer
import com.varabyte.kobweb.cli.common.version.isSnapshot
import com.varabyte.kobweb.cli.common.version.kobwebCliVersion
import com.varabyte.kobweb.cli.common.version.reportUpdateAvailable
import com.varabyte.kobweb.cli.conf.handleConf
import com.varabyte.kobweb.cli.create.handleCreate
import com.varabyte.kobweb.cli.export.handleExport
import com.varabyte.kobweb.cli.help.KotterHelpFormatter
import com.varabyte.kobweb.cli.list.handleList
import com.varabyte.kobweb.cli.run.handleRun
import com.varabyte.kobweb.cli.stop.handleStop
import com.varabyte.kobweb.cli.version.handleVersion
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.SiteLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private fun ParameterHolder.layout() = option(
    "-l", "--layout",
    help = "Specify the organizational layout of the site files."
)
    .enum<SiteLayout>()
    .default(SiteLayout.FULLSTACK)

enum class TeleTypeMode {
    ENABLED,
    DISABLED,
}

fun TeleTypeMode?.shouldUseAnsi(): Boolean {
    return this == null || this == TeleTypeMode.ENABLED
}

// We use `absoluteFile` so that the parent directories are directly accessible. This is necessary for the gradle
// tooling api to be able to get the root project configuration if the kobweb module is a subproject.
private fun ParameterHolder.path() = option(
    "-p", "--path",
    help = "The path to the Kobweb application module.",
)
    .file(mustExist = true, canBeFile = false)
    .convert { it.absoluteFile as File } // cast platform type to explicitly not nullable
    .default(File(".").absoluteFile, defaultForHelp = "the current directory")

private fun ParameterHolder.ttyMode() = mutuallyExclusiveOptions(
    option(
        "-t", "--tty",
        help = "Enable TTY support (default). Tries to run using ANSI support in an interactive mode if it can. Falls back to --notty otherwise."
    ).flag().convert { TeleTypeMode.ENABLED },
    option(
        "--notty",
        help = "Explicitly disable TTY support. In this case, runs in plain mode, logging output sequentially without listening for user input, which is useful for CI environments or Docker containers.",
    ).flag().convert { TeleTypeMode.DISABLED },
).single()

private fun ParameterHolder.gradleArgs(suffix: String? = null) = option(
    "--gradle" + (suffix?.let { "-$it" } ?: ""),
    help =
    if (suffix == null) {
        "Arguments that will be passed into every Gradle call issued by this command (some Kobweb commands have multiple phases), useful for common configurations like --quiet. Surround with quotes for multiple arguments or if there are spaces."
    } else {
        "Arguments that will be passed to the Gradle call associated with the \"$suffix\" phase specifically."
    }
)
    .convert { args -> args.split(' ').filter { it.isNotBlank() } }
    .default(emptyList(), defaultForHelp = "none")

open class NoOpCliktCommand : CoreCliktCommand() {
    override fun run() {}
}

fun main(args: Array<String>) {
    Globals[ProgramArgsKey] = args

    /**
     * Common functionality for all Kobweb subcommands.
     */
    abstract class KobwebSubcommand(private val help: String) : CoreCliktCommand() {
        private var newVersionAvailable: SemVer.Parsed? = null

        override fun help(context: Context): String = help

        /**
         * If true, do an upgrade check while this command is running.
         *
         * If one is available, show an upgrade message after the command finishes running.
         *
         * This value should generally be false unless the command is one that is long-running where a message showing
         * up after it is finished wouldn't be considered intrusive.
         */
        protected open fun shouldCheckForUpgrade(): Boolean = false

        private fun checkForUpgradeAsync() {
            // No need to check for new versions if we're in development mode
            // (which is the only time we'd see a snapshot version here).
            if (kobwebCliVersion.isSnapshot) return

            CoroutineScope(Dispatchers.IO).launch {
                val client = OkHttpClient()
                val latestVersionRequest =
                    Request.Builder()
                        .url("https://raw.githubusercontent.com/varabyte/data/main/kobweb/cli-version.txt")
                        .build()

                client.newCall(latestVersionRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.trim()
                            ?.let { latestVersionStr -> SemVer.tryParse(latestVersionStr) }
                            ?.let { latestVersion ->
                                if (kobwebCliVersion < latestVersion) {
                                    newVersionAvailable = latestVersion
                                }
                            }
                    }
                }
            }
        }

        override fun run() {
            if (shouldCheckForUpgrade()) checkForUpgradeAsync()

            doRun()

            // If we were able to connect in time AND see that a new version is available, then show an upgrade
            // message. Worst case if the command finished running before we were able to check? We don't show the
            // message, but that's not a big deal.
            newVersionAvailable?.let { newVersion ->
                // If we can create a session, that means we're in a TTY and a user is likely watching.
                // In that case, we can print a message to upgrade. Otherwise, e.g. if on a CI, this should
                // just fail silently.
                trySession { this.reportUpdateAvailable(kobwebCliVersion, newVersion) }
            }
        }

        protected abstract fun doRun()
    }

    // The Kobweb command itself doesn't do anything; it delegates everything to subcommands.
    class Kobweb : NoOpCliktCommand() {
        init {
            context {
                helpFormatter = { context ->
                    KotterHelpFormatter(
                        context = context,
                        showDefaultValues = true,
                    )
                }
                helpOptionNames += "help" // Allows "kobweb help" to work
            }
        }

    }

    class Version : KobwebSubcommand(help = "Print the version of this binary") {
        override fun doRun() {
            handleVersion()
        }
    }

    class List : KobwebSubcommand(help = "List all project templates") {
        val repo by option(help = "The repository that hosts Kobweb templates.").default(DEFAULT_REPO)
        val branch by option(help = "The branch in the repository to use. If not specified, git will attempt to use the repo's default branch.")

        override fun shouldCheckForUpgrade() = true
        override fun doRun() {
            handleList(repo, branch)
        }
    }

    class Create : KobwebSubcommand(help = "Create a Kobweb app / site from a template") {
        val template by argument(help = "The name of the template to instantiate, e.g. 'app'. If not specified, choices will be presented.").optional()
        val repo by option(help = "The repository that hosts Kobweb templates.").default(DEFAULT_REPO)
        val branch by option(help = "The branch in the repository to use. If not specified, git will attempt to use the repo's default branch.")

        // Don't check for an upgrade on create, because the user probably just installed kobweb anyway, and the update
        // message kind of overwhelms the instructions to start running the app.
        override fun shouldCheckForUpgrade() = false

        override fun doRun() {
            handleCreate(repo, branch, template)
        }
    }

    class Export : KobwebSubcommand(help = "Generate a static version of a Kobweb app / site") {
        val ttyMode by ttyMode()
        val layout by layout()
        val path by path()
        val gradleArgsCommon by gradleArgs()
        val gradleArgsExport by gradleArgs("export")
        val gradleArgsStop by gradleArgs("stop")

        override fun shouldCheckForUpgrade() = ttyMode.shouldUseAnsi()
        override fun doRun() {
            handleExport(
                path,
                layout,
                ttyMode.shouldUseAnsi(),
                gradleArgsCommon,
                gradleArgsExport,
                gradleArgsStop
            )
        }
    }

    class Run : KobwebSubcommand(help = "Run a Kobweb server") {
        val env by option(help = "Whether the server should run in development mode or production.").enum<ServerEnvironment>()
            .default(ServerEnvironment.DEV)
        val ttyMode by ttyMode()
        val foreground by option(
            "-f",
            "--foreground",
            help = "Keep kobweb running in the foreground. This value can only be specified in --notty mode."
        ).flag(default = false)
        val layout by layout()
        val path by path()
        val gradleArgsCommon by gradleArgs()
        val gradleArgsStart by gradleArgs("start")
        val gradleArgsStop by gradleArgs("stop")

        override fun shouldCheckForUpgrade() = ttyMode.shouldUseAnsi()
        override fun doRun() {
            if (foreground && ttyMode != TeleTypeMode.DISABLED) {
                throw object : UsageError(null) {
                    override fun formatMessage(localization: Localization, formatter: ParameterFormatter): String {
                        return "The foreground flag is only valid when running in ${formatter.formatOption("--notty")} mode."
                    }
                }
            }
            handleRun(
                env,
                path,
                layout,
                ttyMode.shouldUseAnsi(),
                foreground,
                gradleArgsCommon,
                gradleArgsStart,
                gradleArgsStop
            )
        }
    }

    class Stop : KobwebSubcommand(help = "Stop a Kobweb server if one is running") {
        val ttyMode by ttyMode()
        val path by path()
        val gradleArgsCommon by gradleArgs()
        val gradleArgsStop by gradleArgs("stop")

        // Don't check for an upgrade on create, because the user probably just installed kobweb anyway, and the update
        // message kind of overwhelms the instructions to start running the app.
        override fun shouldCheckForUpgrade() = false

        override fun doRun() {
            handleStop(path, ttyMode.shouldUseAnsi(), gradleArgsCommon, gradleArgsStop)
        }
    }

    class Conf : KobwebSubcommand(help = "Query a value from the .kobweb/conf.yaml file (e.g. \"server.port\")") {
        val query by argument(help = "The query to search the .kobweb/conf.yaml for (e.g. \"server.port\"). If not specified, this command will list all possible queries.").optional()
        val path by path()

        override fun doRun() {
            handleConf(query, path)
        }
    }

    // Special-case handling for `kobweb -v` and `kobweb --version`, which are special-cased since it's a format that
    // is expected for many tools.
    if (args.size == 1 && (args[0] == "-v" || args[0] == "--version")) {
        handleVersion()
        return
    }

    Kobweb()
        .subcommands(Version(), List(), Create(), Export(), Run(), Stop(), Conf())
        .main(args)
}
