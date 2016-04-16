package co.arichardson.gradle.make.context

import org.gradle.nativeplatform.NativeBinarySpec

class BuildTaskContext extends NativeBinaryContext {
    final List<File> linkedLibraries = []

    String executable = "make"
    List<String> args = []
    Map<String, String> environment

    BuildTaskContext(NativeBinarySpec binary) {
        super(binary)

        binary.libs*.linkFiles.each {
            linkedLibraries.addAll(it.files)
        }
    }

    @Override
    boolean equals(Object other) {
        other in BuildTaskContext &&
            executable == other.executable &&
            args == other.args &&
            environment == other.environment
    }
}
