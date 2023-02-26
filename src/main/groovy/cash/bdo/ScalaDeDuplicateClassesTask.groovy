/*
 * Copyright (C) 2023-present, Chenai Nakam(chenai.nakam@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cash.bdo

import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.internal.file.DefaultFileTreeElement
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.nativeintegration.filesystem.FileSystem
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges

import javax.inject.Inject

/**
 * @author Chenai Nakam(chenai.nakam@gmail.com)
 * @version 1.0 24/02/2023
 */
abstract class ScalaDeDuplicateClassesTask extends DefaultTask {
    @Inject
    abstract FileSystem getFileSystem()

    @Internal
    abstract Property<String> getNAME_PLUGIN()

    @Internal
    abstract Property<Logger> getLOG()
    //@Internal
    //abstract Property<Integer> getInputDirPathLength()

    @Internal
    abstract SetProperty<String> getPackageOrNamesEvicts()

    @Internal
    abstract SetProperty<String> getPackageOrNamesExcludes()

    @Incremental
    @InputDirectory
    abstract DirectoryProperty getInputDir()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @TaskAction
    def execute(InputChanges inputChanges) {
        // TODO: 实测，输入输出目录不应相同，会导致该任务始终处于`UP-TO-DATE`状态。
        final boolean isInOutSame = isInOutTheSameDir(inputDir.get(), outputDir.get())
        if (isInOutSame) LOG.get().warn "${NAME_PLUGIN.get()} ---> [deduplicate.execute] The `inputDir` was detected to be the same as `outputDir`, the result may be incorrect!"

        inputChanges.getFileChanges(inputDir).each { change ->
            switch (change.fileType) {
                case FileType.MISSING: return
                case FileType.DIRECTORY:
                    // 下面的`DefaultFileTreeElement.copyTo()`有`mkdirs()`操作
                    //outputDir.file(change.normalizedPath).get().asFile.mkdirs()
                    return
            }

            final inputDirPath = inputDir.get().asFile.path
            final inputDirPathLength = inputDirPath.length() + (inputDirPath.endsWith('/') ? 0 : 1)

            final pathRelative = change.normalizedPath.substring(inputDirPathLength)
            final targetFile = isInOutSame
                    // 但这里即使`inputDir`与`outputDir`相同，也要创建新的文件。否则删不掉。
                    ? outputDir.file(change.normalizedPath).get().asFile
                    // 只有这样才能创建真正位于`outputDir`下的文件。否则如果像上一行那样，即使`inputDir`与`outputDir`不同，targetFile 也与`change.file`相同。
                    : outputDir.file(pathRelative).get().asFile

            //noinspection GroovyFallthrough
            switch (change.changeType) {
                case ChangeType.REMOVED:
                    targetFile.delete()
                    break
                case ChangeType.ADDED:
                case ChangeType.MODIFIED:
                    if (isFileHitEvict(change.file, inputDirPathLength)) targetFile.delete() //.
                    else if (!isInOutSame) {
                        LOG.get().info "${NAME_PLUGIN.get()} ---> [deduplicate.execute]copy to:$targetFile"
                        DefaultFileTreeElement.of(change.file, fileSystem).copyTo(targetFile)
                    }
                    break
            }
        }
    }

    boolean isInOutTheSameDir(Directory input, Directory output) {
        return input.asFile.path == output.asFile.path
    }

    boolean isFileHitEvict(File file, int inputDirPathLength) {
        final int destLen = inputDirPathLength
        final Set<String> excludes = packageOrNamesEvicts.get() + packageOrNamesExcludes.get()
        if (excludes.isEmpty()) {
            // androidTest, unitTest 为空较为正常。
            // 由于这个插件是我写的，如果有错，也是我的错（不是用户的错），所以…不能抛异常，最多给个警告表示有这回事即可。
            LOG.get().info "${NAME_PLUGIN.get()} ---> [deduplicate.isFileHitEvict] Are the parameters set correctly? `packageOrNamesEvicts` and `packageOrNamesExcludes` are both empty, and the output may cause duplication error!"
            //return false // 下面的`excludes.any{}`也会立即返回 false。
        }
        final hit = excludes.any { pkgName ->
            //file.path.substring(destLen) == (pkgName + '.class') // 可能后面跟的不是`.class`而是`$1.class`、`$xxx.class`。
            //file.path.substring(destLen).startsWith(pkgName) // 改写如下：
            file.path.indexOf(pkgName, destLen) == destLen && file.path.indexOf('/', destLen + pkgName.length()) < 0
                    //hobby/wei/c/L$3.class
                    //hobby/wei/c/LOG.class
                    && (file.path.indexOf(pkgName + '.', destLen) == destLen || file.path.indexOf(pkgName + '$', destLen) == destLen)
        }
        LOG.get().info "${NAME_PLUGIN.get()} ---> [deduplicate.isFileHitEvict] ${hit ? '^^^' : 'NOT'} HIT:$file"
        return hit
    }
}
