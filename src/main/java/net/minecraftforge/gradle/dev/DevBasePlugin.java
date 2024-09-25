package net.minecraftforge.gradle.dev;

import com.google.common.io.ByteStreams;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.sc.seis.launch4j.Launch4jPluginExtension;
import groovy.util.MapEntry;
import net.minecraftforge.gradle.ArchiveTaskHelper;
import net.minecraftforge.gradle.FileUtils;
import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.json.JsonFactory;
import net.minecraftforge.gradle.json.version.Library;
import net.minecraftforge.gradle.json.version.OS;
import net.minecraftforge.gradle.tasks.CopyAssetsTask;
import net.minecraftforge.gradle.tasks.GenSrgTask;
import net.minecraftforge.gradle.tasks.MergeJarsTask;
import net.minecraftforge.gradle.tasks.abstractutil.DownloadTask;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.dev.CompressLZMA;
import net.minecraftforge.gradle.tasks.dev.ObfuscateTask;
import org.apache.shiro.util.AntPathMatcher;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.process.ExecResult;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public abstract class DevBasePlugin extends BasePlugin<DevExtension> {
    private final AntPathMatcher antMatcher = new AntPathMatcher();
    protected static final String[] JAVA_FILES = new String[]{"**.java", "*.java", "**/*.java"};

    @Override
    public void applyPlugin() {
        makeTask("extractWorkspace", ExtractTask.class, task -> {
            task.getOutputs().upToDateWhen(task1 -> {
                File file = new File(project.getProjectDir(), "eclipse");
                return (file.exists() && file.isDirectory());
            });
            task.from(delayedFile(DevConstants.WORKSPACE_ZIP));
            task.into(delayedFile(DevConstants.WORKSPACE));
        });

        if (hasInstaller()) {
            // apply L4J
            this.applyExternalPlugin("launch4j");

            configureTaskIfPresent(project, "uploadArchives", task -> task.dependsOn("launch4j"));

            makeTask("downloadBaseInstaller", DownloadTask.class, task -> {
                task.setOutput(delayedFile(DevConstants.INSTALLER_BASE));
                task.setUrl(delayedString(DevConstants.INSTALLER_URL));
            });

            makeTask("downloadL4J", DownloadTask.class, task -> {
                task.getLogger().warn("url not available, downloadL4J task shouldn't called");
                task.setOutput(delayedFile(DevConstants.LAUNCH4J));
                task.setUrl(delayedString(DevConstants.LAUNCH4J_URL));
            });

            makeTask("extractL4J", ExtractTask.class, task -> {
                task.dependsOn("downloadL4J");
                task.from(delayedFile(DevConstants.LAUNCH4J));
                task.into(delayedFile(DevConstants.LAUNCH4J_DIR));
            });
        }

        makeTask("updateJson", DownloadTask.class, task -> { // TODO: url not available, this task shouldn't called
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.setUrl(delayedString(Constants.MC_JSON_URL));
            task.setOutput(delayedFile(DevConstants.JSON_BASE));
            task.doLast(new Action<Task>() {
                @Override
                public void execute(Task task) {
                    task.getLogger().warn("url not available, this task shouldn't called");
                    try {
                        File json = delayedFile(DevConstants.JSON_BASE).call();
                        if (!json.exists())
                            return;
                        List<String> lines = Files.readAllLines(json.toPath());
                        StringBuilder buf = new StringBuilder();
                        for (String line : lines) {
                            buf.append(line).append('\n');
                        }
                        Files.write(json.toPath(), buf.toString().getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        });

        makeTask("compressDeobfData", CompressLZMA.class, task -> {
            task.setInputFile(delayedFile(DevConstants.NOTCH_2_SRG_SRG));
            task.setOutputFile(delayedFile(DevConstants.DEOBF_DATA));
            task.dependsOn("genSrgs");
        });

        makeTask("mergeJars", MergeJarsTask.class, task -> {
            task.setClient(delayedFile(Constants.JAR_CLIENT_FRESH));
            task.setServer(delayedFile(Constants.JAR_SERVER_FRESH));
            task.setOutJar(delayedFile(Constants.JAR_MERGED));
            task.setMergeCfg(delayedFile(DevConstants.MERGE_CFG));
            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.dependsOn("downloadClient", "downloadServer"/*, "updateJson"*/); // TODO: Add back updateJson task
        });

        makeTask("copyAssets", CopyAssetsTask.class, task -> {
            task.setAssetsDir(delayedFile(Constants.ASSETS));
            task.setOutputDir(delayedFile(DevConstants.ECLIPSE_ASSETS));
            task.setAssetIndex(getAssetIndexSupplier());
            task.dependsOn("getAssets", "extractWorkspace");
        });

        makeTask("genSrgs", GenSrgTask.class, task -> {
            task.setInSrg(delayedFile(DevConstants.JOINED_SRG));
            task.setInExc(delayedFile(DevConstants.JOINED_EXC));
            task.setMethodsCsv(delayedFile(DevConstants.METHODS_CSV));
            task.setFieldsCsv(delayedFile(DevConstants.FIELDS_CSV));
            task.setNotchToSrg(delayedFile(DevConstants.NOTCH_2_SRG_SRG));
            task.setNotchToMcp(delayedFile(DevConstants.NOTCH_2_MCP_SRG));
            task.setSrgToMcp(delayedFile(DevConstants.SRG_2_MCP_SRG));
            task.setMcpToSrg(delayedFile(DevConstants.MCP_2_SRG_SRG));
            task.setMcpToNotch(delayedFile(DevConstants.MCP_2_NOTCH_SRG));
            task.setSrgExc(delayedFile(DevConstants.SRG_EXC));
            task.setMcpExc(delayedFile(DevConstants.MCP_EXC));

            task.dependsOn("extractMcpData");
        });
    }

    @Override
    public final void applyOverlayPlugin() {
        // nothing.
    }

    @Override
    public final boolean canOverlayPlugin() {
        return false;
    }

    private void configureLaunch4J() {
        if (!hasInstaller())
            return;

        final File installer = ArchiveTaskHelper.getArchivePath((Zip) project.getTasks().getByName("packageInstaller"));

        File output = new File(installer.getParentFile(), installer.getName().replace(".jar", "-win.exe"));
        project.getArtifacts().add("archives", output);

        Launch4jPluginExtension ext = (Launch4jPluginExtension) project.getExtensions().getByName("launch4j");
        ext.setOutfile(output.getAbsolutePath());
        ext.setJar(installer.getAbsolutePath());

        String command = delayedFile(DevConstants.LAUNCH4J_DIR).call().getAbsolutePath();
        command += "/launch4j";

        if (Constants.OPERATING_SYSTEM == OS.WINDOWS)
            command += "c.exe";
        else {
            final String extraCommand = command;

            configureTask("extractL4J", task -> task.doLast(new Action<Task>() {
                @Override
                public void execute(Task task) {
                    File f = new File(extraCommand);
                    if (!f.canExecute()) {
                        boolean worked = f.setExecutable(true);
                        task.getLogger().debug("Setting file +X {} : {}", worked, f.getPath());
                    }
                    FileTree tree = delayedFileTree(DevConstants.LAUNCH4J_DIR + "/bin").call();
                    tree.visit(visitDetails -> {
                        if (!visitDetails.isDirectory()) {
                            File file = visitDetails.getFile();
                            if (!file.canExecute()) {
                                boolean worked = file.setExecutable(true);
                                task.getLogger().debug("Setting file +X {} : {}", worked, visitDetails.getPath());
                            }
                        }
                    });
                }
            }));
        }

        ext.setLaunch4jCmd(command);

        configureTask("generateXmlConfig", task -> {
            task.dependsOn("packageInstaller", "extractL4J");
            task.getInputs().file(installer);
        });

        String icon = ext.getIcon();
        if (icon == null || icon.isEmpty()) {
            icon = delayedFile(DevConstants.LAUNCH4J_DIR + "/demo/SimpleApp/l4j/SimpleApp.ico").call().getAbsolutePath();
        }
        icon = new File(icon).getAbsolutePath();
        ext.setIcon(icon);
        ext.setMainClassName(delayedString("{MAIN_CLASS}").call());
    }

    @Override
    protected DelayedFile getDevJson() {
        return delayedFile(DevConstants.JSON_DEV);
    }

    @Override
    public void afterEvaluate() {
        super.afterEvaluate();

        configureLaunch4J();

        // set obfuscate extras
        this.<ObfuscateTask>configureTask("obfuscateJar", task -> {
            task.setExtraSrg(getExtension().getSrgExtra());
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getDirtyProject());
        });

        makeTask("extractNativesNew", ExtractTask.class, task -> {
            task.exclude("META-INF", "META-INF/**", "META-INF/*");
            task.into(delayedFile(Constants.NATIVES_DIR));
        });

        makeTask("extractNatives", Copy.class, task -> {
            task.from(delayedFile(Constants.NATIVES_DIR));
            task.exclude("META-INF", "META-INF/**", "META-INF/*");
            task.into(delayedFile(DevConstants.ECLIPSE_NATIVES));
            task.dependsOn("extractWorkspace", "extractNativesNew");
        });

        DelayedFile devJson = getDevJson();
        if (devJson == null) {
            project.getLogger().info("Dev json not set, could not create native downloads tasks");
            return;
        }

        if (version == null) {
            File jsonFile = devJson.call().getAbsoluteFile();
            try {
                version = JsonFactory.loadVersion(jsonFile, jsonFile.getParentFile());
            } catch (IOException e) {
                project.getLogger().error("{} could not be parsed", jsonFile);
                throw new RuntimeException(e);
            }
        }

        for (Library lib : version.getLibraries()) {
            if (lib.natives != null) {
                String path = lib.getPathNatives();
                String taskName = "downloadNatives-" + lib.getArtifactName().split(":")[1];

                makeTask(taskName, DownloadTask.class, task -> {
                    task.setOutput(delayedFile("{CACHE_DIR}/minecraft/" + path));
                    task.setUrl(delayedString(lib.getUrl() + path));
                });

                this.<ExtractTask>configureTask("extractNativesNew", task -> {
                    task.from(delayedFile("{CACHE_DIR}/minecraft/" + path));
                    task.dependsOn(taskName);
                });
            }
        }
    }

    protected Class<DevExtension> getExtensionClass() {
        return DevExtension.class;
    }

    protected DevExtension getOverlayExtension() {
        // never happens.
        return null;
    }

    protected String getServerClassPath(File json) {
        try {
            JsonObject node = JsonParser.parseString(FileUtils.readString(json)).getAsJsonObject();

            StringBuilder buf = new StringBuilder();

            for (JsonElement libElement : node.get("versionInfo").getAsJsonObject().get("libraries").getAsJsonArray()) {
                JsonObject lib = libElement.getAsJsonObject();

                if (lib.has("serverreq") && lib.get("serverreq").getAsBoolean()) {
                    String[] pts = lib.get("name").getAsString().split(":");
                    buf.append(String.format("libraries/%s/%s/%s/%s-%s.jar ", pts[0].replace('.', '/'), pts[1], pts[2], pts[1], pts[2]));
                }
            }
            buf.append(delayedString("minecraft_server.{MC_VERSION}.jar").call());
            return buf.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String resolve(String pattern, Project project, DevExtension exten) {
        pattern = super.resolve(pattern, project, exten);

        // MCP_DATA_DIR wont be resolved if the data dir doesnt eixts,,, hence...
        pattern = pattern.replace("{MCP_DATA_DIR}", "{FML_CONF_DIR}");

        // For simplicities sake, if the version is in the standard format of {MC_VERSION}-{realVersion}
        // lets trim the MC version from the replacement string.
        String version = project.getVersion().toString();
        String mcSafe = exten.getVersion().replace('-', '_');
        if (version.startsWith(mcSafe + "-")) {
            version = version.substring(mcSafe.length() + 1);
        }
        pattern = pattern.replace("{VERSION}", version);
        pattern = pattern.replace("{MAIN_CLASS}", exten.getMainClass());
        pattern = pattern.replace("{FML_TWEAK_CLASS}", exten.getTweakClass());
        pattern = pattern.replace("{INSTALLER_VERSION}", exten.getInstallerVersion());
        pattern = pattern.replace("{FML_DIR}", exten.getFmlDir());
        pattern = pattern.replace("{FORGE_DIR}", exten.getForgeDir());
        pattern = pattern.replace("{BUKKIT_DIR}", exten.getBukkitDir());
        pattern = pattern.replace("{FML_CONF_DIR}", exten.getFmlDir() + "/conf");
        return pattern;
    }

    @Nullable
    protected static String runGit(final Project project, final File workDir, final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecResult git = project.exec(exec -> {
            exec.setExecutable("git");
            exec.args((Object[]) args);
            exec.setStandardOutput(out);
            exec.setWorkingDir(workDir);
            exec.setIgnoreExitValue(true);
        });
        if (git.getExitValue() != 0) {
            return null;
        }

        return out.toString().trim();
    }

    private boolean shouldSign(String path, List<String> includes, List<String> excludes) {
        for (String exclude : excludes) {
            if (antMatcher.matches(exclude, path)) {
                return false;
            }
        }

        for (String include : includes) {
            if (antMatcher.matches(include, path)) {
                return true;
            }
        }

        return includes.isEmpty(); //If it gets to here, then it matches nothing. default to true, if no includes were specified
    }

    @SuppressWarnings("unchecked")
    protected void signJar(File archive, String keyName, String... filters) throws IOException {
        if (!project.hasProperty("jarsigner")) return;

        List<String> excludes = new ArrayList<>();
        List<String> includes = new ArrayList<>();

        for (String s : filters) {
            if (s.startsWith("!")) excludes.add(s.substring(1));
            else includes.add(s);
        }

        Map<String, Map.Entry<byte[], Long>> unsigned = new HashMap<>();
        Path temp = new File(archive.getAbsoluteFile() + ".tmp").toPath();
        Path signed = new File(archive.getAbsoluteFile() + ".signed").toPath();

        Files.deleteIfExists(temp);
        Files.deleteIfExists(signed);

        // Create a temporary jar with only the things we want to sign
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)));
             ZipFile base = new ZipFile(archive)) {
            for (ZipEntry e : Collections.list(base.entries())) {
                if (shouldSign(e.getName(), includes, excludes)) {
                    ZipEntry n = new ZipEntry(e.getName());
                    n.setTime(e.getTime());
                    out.putNextEntry(n);
                    ByteStreams.copy(base.getInputStream(e), out);
                } else {
                    unsigned.put(e.getName(), new MapEntry(ByteStreams.toByteArray(base.getInputStream(e)), e.getTime()));
                }
            }
        }

        // Sign the temporary jar
        Map<String, String> jarsigner = (Map<String, String>) project.property("jarsigner");

        Map<String, String> args = new HashMap<>();
        args.put("alias", keyName);
        args.put("storepass", jarsigner.get("storepass"));
        args.put("keypass", jarsigner.get("keypass"));
        args.put("keystore", new File(jarsigner.get("keystore")).getAbsolutePath());
        args.put("jar", temp.toAbsolutePath().toString());
        args.put("signedjar", signed.toAbsolutePath().toString());
        project.getAnt().invokeMethod("signjar", args);

        //Kill temp files to make room
        Files.delete(archive.toPath());
        Files.delete(temp);

        //Join the signed jar and our unsigned content
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive.toPath())))) {
            try (ZipFile zipFile = new ZipFile(signed.toFile())) {
                for (ZipEntry e : Collections.list(zipFile.entries())) {
                    if (e.isDirectory()) {
                        zipOutputStream.putNextEntry(e);
                    } else {
                        ZipEntry n = new ZipEntry(e.getName());
                        n.setTime(e.getTime());
                        zipOutputStream.putNextEntry(n);
                        ByteStreams.copy(zipFile.getInputStream(e), zipOutputStream);
                    }
                }
            }

            for (Map.Entry<String, Map.Entry<byte[], Long>> e : unsigned.entrySet()) {
                ZipEntry n = new ZipEntry(e.getKey());
                n.setTime(e.getValue().getValue());
                zipOutputStream.putNextEntry(n);
                zipOutputStream.write(e.getValue().getKey());
            }
        }
        Files.delete(signed);
    }

    protected boolean hasInstaller() {
        return true;
    }
}