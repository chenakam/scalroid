package org.example

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GreetingToFileTask extends DefaultTask {
    @OutputFile
    abstract RegularFileProperty getDestination()

    @TaskAction
    def greet() {
        def file = destination.get().asFile
        file.parentFile.mkdirs()
        file.write 'Hello!'

        println("write to file `${file.name}` succeed~")

        // test throws:
//        throw new RuntimeException()
    }
}
