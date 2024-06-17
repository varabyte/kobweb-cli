# This file contain the rules that's either specific to the project or custom rules that's not from the dependencies
# and it doesn't contain the required configurations as it's already configured by Gradle task

# Proguard Kotlin Example https://github.com/Guardsquare/proguard/blob/master/examples/application-kotlin/proguard.pro

-keepattributes *Annotation*

# Entry point to the app.
-keep class MainKt { *; }

# Ignore all the warnings from Kotlinx Coroutines
-dontwarn kotlinx.coroutines.**

# Leave Jansi deps in place, or else Windows won't work
-keep class org.fusesource.jansi.** { *; }
-keep class org.jline.jline-terminal-jansi.** { *; }

# Exclude SLF4J from minimization
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Suppress warnings coming from Gradle API
-dontwarn org.gradle.**

# Suppress and fix Freemarker warnings
-dontwarn freemarker.ext.**
-dontwarn freemarker.template.utility.JythonRuntime
-keep class freemarker.template.utility.JythonRuntime

# Suppress warnings about missing classes from 'org.python package'
# Freemarker relies on Jython, and some Jython classes may not be recognized.
-dontwarn org.python.**

# Suppress warnings from Javax Servlet as Freemarker depends on it and is already part of the JAR
-dontwarn javax.servlet.**

# Suppress warnings from Apache Logging as Freemarker depends on it
-dontwarn org.apache.log4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.log.**