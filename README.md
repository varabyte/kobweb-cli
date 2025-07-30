This project is the CLI binary for the [Kobweb framework](https://github.com/varabyte/kobweb).

## Building

### Dev

For local development...

* Check your `libs.versions.toml` file.
  * `kobweb-cli` should have a version with a `-SNAPSHOT` suffix.

* Install the binary: `./gradlew :kobweb:installShadowDist`
  * This will create `kobweb/build/install/kobweb/bin/kobweb`.
  * You are encouraged to create a symlink to it which lives in your path, so you can run the `kobweb` command from
    anywhere.

### Prod

For a release...

* Check your `libs.versions.toml` file.
  * `kobweb-cli` should have a version that does NOT end with a `-SNAPSHOT` suffix.

* Assemble the tar and zip files: `./gradlew :kobweb:assembleShadowDist`
  * Files live under `kobweb/build/distributions`.

> [!IMPORTANT]
> The Kobweb CLI project has a [build workflow](.github/workflows/build.yml) which generates CLI artifacts every time a
> new commit is pushed.
>
> You can find these by clicking on the relevant [build run](https://github.com/varabyte/kobweb-cli/actions/workflows/build.yml)
> and then downloading the `kobweb-cli-artifacts` zip from the `Artifacts` section).
>
> You should consider using these instead of ones you built yourself, as the CI environment is guaranteed to be pure,
> whereas local environments may be contaminated by things you've installed or set up on your own system.

## Releasing

* Create a new release on GitHub.
* Choose a tag: "vX.Y.Z", then "Create a new tag on publish"
* Set that tag for the release title as well
* Fill out the release, using previous releases as guidance (and comparing changes to main since last time to see what's
  new)
* Add the .zip and .tar files downloaded from GitHub actions or, if built manually, from `kobweb/build/distributions`
* Confirm the release.

## Publishing

> [!IMPORTANT]
> To successfully publish the CLI, the version must NOT be set to a SNAPSHOT version.

> [!CAUTION]
> Be very careful with this step. If you publish things from the wrong branch, you could make a mess that could take a
> while to clean up.

* From https://github.com/varabyte/kobweb-cli/actions choose the "Publish" workflow.
* Be sure to select the correct target (should be a branch or tag targeting the version you just released).
* Uncheck the "Dry run" checkbox. (Although you may want to do a dry run first to make sure everything is set up
  correctly.)
* Run the workflow.

### Manual publishing

* Set the Gradle property `kobweb.cli.jreleaser.dryrun` to false.
* Run `./gradlew :kobweb:jreleaserPublish`

Publishing from your machine requires you have defined the following secrets locally:

* varabyte.github.username
* varabyte.github.token
* sdkman.key

and the github user specified must have access to edit the `varabyte` organization, as the publish process modifies
other sibling repositories as a side effect.

## Informing users about the release

* Update the badge version at the top of the main Kobweb README
* Update the version file: https://github.com/varabyte/data/blob/main/kobweb/cli-version.txt
* Create an announcement in all relevant Kobweb communities (Discord, Slack, etc.)
