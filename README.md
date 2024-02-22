This project is the CLI binary for the Kobweb framework.

## Background

Kobweb is a Kotlin framework for building reactive web applications.
You can view the project here: (https://github.com/varabyte/kobweb)

The code in here used to be part of the main Kobweb codebase, but it made sense to split it out into its own project for
a couple of reasons:

* It has its own release cadence completely separate from the Kobweb libraries.
* It is updated far less often than Kobweb is.
* In GitHub, it was very hard to do a diff between two CLI releases, since one had to read past all the library commits
  that were there.
* It is common while working on Kobweb to clean and rebuild all library artifacts, but doing so often deletes the CLI at
  the same time.
* Most of the dependencies used by the CLI are completely different to those used by Kobweb, making the
  libs.versions.toml file feel a bit crowded.

This binary does depend on one of the Kobweb artifacts, a library called `kobweb-common`, and in the early days of
Kobweb was development, it required frequent updating. More recently, though, this isn't the case anymore, reducing the
friction of pulling this project out into its own repository.

If we ever need to make a change to `kobweb-common` again for code used by this project, we can just upload an artifact
snapshot and have this binary depend on it. This is a bit of a pain, but it's not a big deal.

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

## Releasing

* Create a new release on GitHub.
* Choose a tag: "vX.Y.Z", then "Create a new tag on publish"
* Set that tag for the release title as well
* Fill out the release, using previous releases as guidance (and comparing changes to main since last time to see what's
  new)
* Add the .zip and .tar files from `kobweb/build/distributions`
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

Although to do successfully publish, you must have defined the following secrets on your machine:

* varabyte.github.username
* varabyte.github.token
* sdkman.key

and the github user specified must have access to edit the `varabyte` organization, as the publish process modifies
other sibling repositories as a side effect.

## Inform users about the release

* Update the badge version at the top of the main Kobweb README
* Update the version file: https://github.com/varabyte/data/blob/main/kobweb/cli-version.txt
* Create an announcement in all relevant Kobweb communities (Discord, Slack, etc.)
