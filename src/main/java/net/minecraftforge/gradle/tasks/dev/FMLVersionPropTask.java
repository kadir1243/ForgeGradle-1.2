package net.minecraftforge.gradle.tasks.dev;

import groovy.lang.Closure;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Supplier;

public class FMLVersionPropTask extends DefaultTask {
    private final String projectVersion = (String) getProject().getVersion();
    @OutputFile
    DelayedFile outputFile;

    private Supplier<String> version;

    @TaskAction
    public void doTask() throws IOException {
        String fullVersion;
        if (this.version == null)
            fullVersion = projectVersion;
        else {
            String string = this.version.get();
            if (string == null || string.trim().isEmpty()) fullVersion = projectVersion;
            else fullVersion = string;
        }

        String mcVersion = new DelayedString(getProject(), "{MC_VERSION}").call();
        fullVersion = fullVersion.substring(mcVersion.length());
        if (!fullVersion.contains("-")) {
            if (projectVersion.contains("-")) {
                String after = projectVersion.substring(projectVersion.lastIndexOf("-") + 1);
                if (after.split("\\.").length == 4) {
                    fullVersion = projectVersion;
                } else {
                    fullVersion = "1.7.10-0.0.0.0";
                }
            } else {
                fullVersion = "1.7.10-0.0.0.0";
            }
        }
        String[] v = fullVersion.split("-")[1].split("\\.");
        String data =
                "fmlbuild.major.number=" + v[0] + "\n" +
                        "fmlbuild.minor.number=" + v[1] + "\n" +
                        "fmlbuild.revision.number=" + v[2] + "\n" +
                        "fmlbuild.build.number=" + v[3] + "\n" +
                        "fmlbuild.mcversion=" + new DelayedString(getProject(), "{MC_VERSION}").call() + "\n" +
                        "fmlbuild.mcpversion=" + new DelayedString(getProject(), "{MCP_VERSION}").call() + "\n";
        //fmlbuild.deobfuscation.hash -- Not actually used anywhere
        Files.write(getOutputFile().toPath(), data.getBytes(StandardCharsets.UTF_8));
    }

    public void setOutputFile(DelayedFile output) {
        this.outputFile = output;
    }

    public File getOutputFile() {
        return outputFile.call();
    }

    @Deprecated
    public void setVersion(Closure<String> value) {
        this.version = value::call;
    }

    public void setVersion(Supplier<String> value) {
        this.version = value;
    }
}
