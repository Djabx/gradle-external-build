package co.arichardson.gradle.make

import co.arichardson.gradle.make.context.BuildTaskContext
import co.arichardson.gradle.make.context.ExternalOutputsContext
import co.arichardson.gradle.make.internal.DefaultExternalNativeLibrarySpec
import co.arichardson.gradle.make.tasks.OutputRedirectingExec
import org.gradle.api.Task
import org.gradle.api.file.CopySpec
import org.gradle.api.internal.ClosureBackedAction
import org.gradle.api.internal.file.FileOperations
import org.gradle.language.cpp.CppSourceSet
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask
import org.gradle.model.ModelMap
import org.gradle.model.Mutate
import org.gradle.model.Path
import org.gradle.model.RuleSource
import org.gradle.nativeplatform.NativeBinarySpec
import org.gradle.nativeplatform.tasks.ObjectFilesToBinary
import org.gradle.platform.base.ComponentType
import org.gradle.platform.base.TypeBuilder

class ExternalBuildPlugin extends RuleSource {
    public static final String EXTERNAL_SOURCE = 'externalSource'
    public static final String EXTERNAL_BUILD_TASK = 'externalBuild'

    @ComponentType
    void registerExternalLibraryType(TypeBuilder<ExternalNativeLibrarySpec> builder) {
        builder.defaultImplementation(DefaultExternalNativeLibrarySpec)
    }

    @Mutate
    void configureExternalLibraries(ModelMap<ExternalNativeLibrarySpec> libraries) {
        libraries.all { library ->
            library.binaries.withType(NativeBinarySpec) { binary ->
                // Evaluate the "externalOutputs" block
                ExternalOutputsContext outputsContext = new ExternalOutputsContext(binary)
                Utils.invokeWithContext(library.externalOutputs, outputsContext)

                // Create the source set to include exported headers
                binary.sources.create(EXTERNAL_SOURCE, CppSourceSet) {
                    it.exportedHeaders.srcDirs = outputsContext.headersContext.srcDirs
                }

                // Disable all normal compile tasks
                binary.tasks.withType(AbstractNativeCompileTask) {
                    it.enabled = false
                }

                // Replace the create/link task with a simple copy
                binary.tasks.withType(ObjectFilesToBinary) { mainTask ->
                    FileOperations ops = mainTask.services.get(FileOperations)

                    mainTask.inputs.file outputsContext.outputFile
                    mainTask.doFirst {
                        ops.copy(new ClosureBackedAction<CopySpec>({
                            it.from outputsContext.outputFile
                            it.into mainTask.outputFile.parentFile
                            it.rename { mainTask.outputFile.name }
                        }))
                    }
                }
            }
        }
    }

    @Mutate
    void createExternalLibraryTasks(ModelMap<Task> tasks, @Path('binaries') ModelMap<NativeBinarySpec> binaries) {
        Map<BuildTaskContext, OutputRedirectingExec> buildTasks = [:]

        binaries.findAll { it.component in ExternalNativeLibrarySpec } .each { NativeBinarySpec binary ->
            ExternalNativeLibrarySpec library = binary.component as ExternalNativeLibrarySpec

            // Create the task configuration
            BuildTaskContext taskContext = new BuildTaskContext(binary)
            Utils.invokeWithContext(library.configureBuild, taskContext)

            // Create the task (or reuse one if an exact duplicate exists)
            OutputRedirectingExec buildTask = buildTasks.find { it.key == taskContext } ?.value
            if (!buildTask) {
                String taskName = binary.tasks.taskName(EXTERNAL_BUILD_TASK)

                binary.tasks.create(taskName, OutputRedirectingExec) {
                    it.executable = taskContext.executable
                    it.args = taskContext.args
                    it.environment = taskContext.environment
                    it.redirectOutput = true
                    buildTask = it
                }

                buildTasks[taskContext] = buildTask
            }

            buildTask.dependsOn(binary.libs*.linkFiles)
            binary.sources.get(EXTERNAL_SOURCE).builtBy(buildTask)
        }
    }
}
