This project is the CLI binary for the Kobweb framework.

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