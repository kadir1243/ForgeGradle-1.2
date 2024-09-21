package net.minecraftforge.gradle.tasks.dev;

import groovy.lang.Closure;
import net.minecraftforge.gradle.FileUtils;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import net.minecraftforge.gradle.json.JsonFactory;
import net.minecraftforge.gradle.json.version.Library;
import net.minecraftforge.gradle.json.version.Version;
import net.minecraftforge.gradle.user.UserConstants;
import org.gradle.api.DefaultTask;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static net.minecraftforge.gradle.common.Constants.NEWLINE;

public class GenDevProjectsTask extends DefaultTask {
    protected DelayedFile targetDir;

    protected DelayedFile json;

    @Input
    @Optional
    private DelayedString mappingChannel, mappingVersion, mcVersion;

    private List<DelayedFile> sources = new ArrayList<>();
    private List<DelayedFile> resources = new ArrayList<>();
    private List<DelayedFile> testSources = new ArrayList<>();
    private List<DelayedFile> testResources = new ArrayList<>();

    private final ArrayList<String> deps = new ArrayList<>();

    public GenDevProjectsTask() {
        this.getOutputs().file(getTargetFile());
    }

    @TaskAction
    public void doTask() throws IOException {
        parseJson();
        writeFile();
    }

    private void parseJson() throws IOException {
        Version version = JsonFactory.loadVersion(getJson(), getJson().getParentFile());

        for (Library lib : version.getLibraries()) {
            if (lib.name.contains("fixed") || lib.natives != null || lib.extract != null) {
                continue;
            } else {
                deps.add(lib.getArtifactName());
            }
        }
    }

    private void writeFile() throws IOException {
        File file = getProject().file(getTargetFile().call());
        file.getParentFile().mkdirs();
        FileUtils.updateDate(file);

        // prepare file string for writing.
        StringBuilder o = new StringBuilder();

        a(o,
                "apply plugin: 'java'",
                "//apply plugin: 'eclipse'",
                "",
                "sourceCompatibility = targetCompatibility = '1.6'",
                "",
                "repositories {",
                "    mavenCentral()",
                "    maven {",
                "        url = 'https://repo1.maven.org/maven2'",
                "    }",
                "    maven {",
                "        name 'forge'",
                "        url '" + Constants.FORGE_MAVEN + '\'',
                "    }",
                "    maven {",
                "        name 'sonatypeSnapshot'",
                "        url 'https://oss.sonatype.org/content/repositories/snapshots/'",
                "    }",
                "    maven {",
                "        name 'minecraft'",
                "        url '" + Constants.LIBRARY_URL + '\'',
                "    }",
                "}",
                "",
                "dependencies {"
        );

        // read json, output json in gradle freindly format...
        for (String dep : deps) {
            o.append("    ").append(UserConstants.CONFIG_COMPILE).append(" '").append(dep).append('\'').append(NEWLINE);
        }

        String channel = getMappingChannel();
        String version = getMappingVersion();
        String mcversion = getMcVersion();
        if (version != null && channel != null) {
            o.append("    ").append(UserConstants.CONFIG_COMPILE).append(" group: 'de.oceanlabs.mcp', name:'mcp_").append(channel).append("', version:'").append(version).append('-').append(mcversion).append("', ext:'zip'");
        }
        a(o,
                "",
                "    " + JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME + " 'junit:junit:4.5'",
                "}",
                ""
        );

        URI base = targetDir.call().toURI();

        if (resources.size() > 0 || sources.size() > 0 || testSources.size() > 0 || testResources.size() > 0) {
            a(o, "sourceSets {");
            a(o, "    main {");
            if (sources.size() > 0) {
                a(o, "        java {");
                for (DelayedFile src : sources) {
                    o.append("            srcDir '").append(relative(base, src)).append('\'').append(NEWLINE);
                }
                a(o, "        }");
            }
            if (resources.size() > 0) {
                a(o, "        resources {");
                for (DelayedFile src : resources) {
                    o.append("            srcDir '").append(relative(base, src)).append('\'').append(NEWLINE);
                }
                a(o, "        }");
            }
            a(o, "    }");
            a(o, "    test {");
            if (testSources.size() > 0) {
                a(o, "        java {");
                for (DelayedFile src : testSources) {
                    o.append("            srcDir '").append(relative(base, src)).append('\'').append(NEWLINE);
                }
                a(o, "        }");
            }
            if (testResources.size() > 0) {
                a(o, "        resources {");
                for (DelayedFile src : testResources) {
                    o.append("            srcDir '").append(relative(base, src)).append('\'').append(NEWLINE);
                }
                a(o, "        }");
            }
            a(o, "    }");
            a(o, "}");
        }

        // and now start stuff
        a(o,
                "",
                "jar { exclude 'GradleStart*', 'net/minecraftforge/gradle/**' }",
                ""
        );

        // and now eclipse hacking
        a(o,
                "if (false) {", //FIXME: Add back
                "   def links = []",
                "   def dupes = []",
                "   eclipse.project.file.withXml { provider ->",
                "       def node = provider.asNode()",
                "       links = []",
                "       dupes = []",
                "       node.linkedResources.link.each { child ->",
                "           def path = child.location.text()",
                "           if (path in dupes) {",
                "               child.replaceNode {}",
                "           } else {",
                "               dupes.add(path)",
                "               def newName = path.split('/')[-2..-1].join('/')",
                "               links += newName",
                "               child.replaceNode {",
                "                   link{",
                "                       name(newName)",
                "                       type('2')",
                "                       location(path)",
                "                   }",
                "               }",
                "           }",
                "       }",
                "   }",
                "   ",
                "   eclipse.classpath.file.withXml {",
                "       def node = it.asNode()",
                "       node.classpathentry.each { child -> ",
                "           if (child.@kind == 'src' && !child.@path.contains('/')) child.replaceNode {}",
                "           if (child.@path in links) links.remove(child.@path)",
                "       }",
                "       links.each { link -> node.appendNode('classpathentry', [kind:'src', path:link]) }",
                "   }",
                "   tasks.eclipseClasspath.dependsOn 'eclipseProject' //Make them run in correct order",
                "}"
        );

        Files.write(file.toPath(), o.toString().getBytes(Charset.defaultCharset()));
    }

    private String relative(URI base, DelayedFile src) {
        String relative = base.relativize(src.call().toURI()).getPath().replace('\\', '/');
        if (!relative.endsWith("/")) relative += "/";
        return relative;
    }

    private void a(StringBuilder out, String... lines) {
        for (String line : lines) {
            out.append(line).append(NEWLINE);
        }
    }

    private Closure<File> getTargetFile() {
        return new Closure<File>(this) {
            private static final long serialVersionUID = -6333350974905684295L;

            @Override
            public File call() {
                return new File(getTargetDir(), "build.gradle");
            }

            @Override
            public File call(Object obj) {
                return new File(getTargetDir(), "build.gradle");
            }
        };
    }

    @OutputDirectory
    public File getTargetDir() {
        return targetDir.call();
    }

    public void setTargetDir(DelayedFile targetDir) {
        this.targetDir = targetDir;
    }

    public GenDevProjectsTask addSource(DelayedFile source) {
        sources.add(source);
        return this;
    }

    public GenDevProjectsTask addResource(DelayedFile resource) {
        resources.add(resource);
        return this;
    }

    public GenDevProjectsTask addTestSource(DelayedFile source) {
        testSources.add(source);
        return this;
    }

    public GenDevProjectsTask addTestResource(DelayedFile resource) {
        testResources.add(resource);
        return this;
    }

    @InputFile
    public File getJson() {
        return json.call();
    }

    @InputDirectory
    public File getJsonParentDir() {
        return getJson().getParentFile();
    }

    public void setJson(DelayedFile json) {
        this.json = json;
    }

    public String getMappingChannel() {
        String channel = mappingChannel.call();
        return channel.equals("{MAPPING_CHANNEL}") ? null : channel;
    }

    public void setMappingChannel(DelayedString mChannel) {
        this.mappingChannel = mChannel;
    }

    public String getMappingVersion() {
        String version = mappingVersion.call();
        return version.equals("{MAPPING_VERSION}") ? null : version;
    }

    public void setMappingVersion(DelayedString mappingVersion) {
        this.mappingVersion = mappingVersion;
    }

    public String getMcVersion() {
        return mcVersion.call();
    }

    public void setMcVersion(DelayedString mcVersion) {
        this.mcVersion = mcVersion;
    }
}
