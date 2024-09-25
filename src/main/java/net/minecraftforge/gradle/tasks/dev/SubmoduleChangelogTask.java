package net.minecraftforge.gradle.tasks.dev;

import net.minecraftforge.gradle.delayed.DelayedFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

public class SubmoduleChangelogTask extends DefaultTask {
    @InputDirectory
    private DelayedFile submodule;
    @Input
    private String moduleName;
    @Input
    private String prefix;
    @OutputFile
    private File outputFile;

    @TaskAction
    public void doTask() throws IOException {
        getLogger().lifecycle("");

        String[] output = runGit(getProject().getProjectDir(), "--no-pager", "diff", "--no-color", "--", getSubmodule().getName());
        if (output.length == 0) {
            getLogger().lifecycle("Could not grab submodule changes");
            return;
        }

        String start = null;
        String end = null;
        for (String line : output) {
            if (line.startsWith("-Subproject commit")) {
                start = line.substring(19);
            } else if (line.startsWith("+Subproject commit")) {
                end = line.substring(19);
                if (line.endsWith("-dirty")) {
                    end = end.substring(0, end.length() - 6);
                }
            }
        }

        if (start == null && end == null) {
            getLogger().lifecycle("Could not extract start and end range");
            return;
        }

        output = runGit(getSubmodule(), "--no-pager", "log", "--reverse", "--pretty=oneline", start + "..." + end);
        getLogger().lifecycle("Updated " + getModuleName() + ":");

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), Charset.defaultCharset())) {
            for (String line : output) {
                String out = getPrefix() + "@" + line;
                getLogger().lifecycle(out);
                writer.write(out);
                writer.newLine();
            }
        }

        getLogger().lifecycle("");
    }

    private String[] runGit(final File dir, final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        getProject().exec(exec -> {
            exec.setExecutable("git");
            exec.args((Object[]) args);
            exec.setStandardOutput(out);
            exec.setWorkingDir(dir);
        });

        return out.toString().trim().split("\n");
    }

    public File getSubmodule() {
        return submodule.call();
    }

    public void setSubmodule(DelayedFile submodule) {
        this.submodule = submodule;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String name) {
        this.moduleName = name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }
}
