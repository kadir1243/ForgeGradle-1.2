package net.minecraftforge.gradle.dev;

//import edu.sc.seis.launch4j.Launch4jPluginExtension;

import net.minecraftforge.gradle.ArchiveTaskHelper;
import net.minecraftforge.gradle.CopyInto;
import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedBase;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.*;
import net.minecraftforge.gradle.tasks.abstractutil.DelayedJar;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.abstractutil.FileFilterTask;
import net.minecraftforge.gradle.tasks.dev.*;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static net.minecraftforge.gradle.dev.DevConstants.*;

public class FmlDevPlugin extends DevBasePlugin {
    @Override
    public void applyPlugin() {
        super.applyPlugin();

        // set fmlDir
        getExtension().setFmlDir(".");

        /* We dont need to add this to ALL this is only used for S2S
        // configure genSrg task.
        GenSrgTask genSrgTask = (GenSrgTask) project.getTasks().getByName("genSrgs");
        {
            // find all the exc & srg files in the resources.
            for (File f : project.fileTree(delayedFile(DevConstants.FML_RESOURCES).call()).getFiles())
            {
                if(f.getPath().endsWith(".exc"))
                    genSrgTask.addExtraExc(f);
                else if(f.getPath().endsWith(".srg"))
                    genSrgTask.addExtraSrg(f);
            }
        }
        */

        //configureLaunch4J();
        createJarProcessTasks();
        createProjectTasks();
        createEclipseTasks();
        createMiscTasks();
        createSourceCopyTasks();
        createPackageTasks();

        // the master setup task.
        makeTask("setupFML", task -> {
            task.dependsOn("extractFmlSources", "generateProjects", EclipsePlugin.ECLIPSE_TASK_NAME, "copyAssets");
            task.setGroup("FML");
        });

        // the master task.
        makeTask("buildPackages", task -> {
            task.dependsOn("launch4j", "createChangelog", "packageUniversal", "packageInstaller", "packageUserDev", "packageSrc");
            task.setGroup("FML");
        });

        // clean decompile task
        makeTask("cleanDecompile", Delete.class, task -> {
            task.delete(delayedFile(DevConstants.ECLIPSE_CLEAN_SRC));
            task.delete(delayedFile(DevConstants.ECLIPSE_FML_SRC));
            task.delete(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.delete(delayedFile(DevConstants.ZIP_PATCHED_FML));
            task.setGroup("Clean");
        });
    }

    protected void createJarProcessTasks() {
        makeTask("deobfuscateJar", ProcessJarTask.class, task -> {
            task.setInJar(delayedFile(Constants.JAR_MERGED));
            task.setOutCleanJar(delayedFile(DevConstants.JAR_SRG_FML));
            task.setSrg(delayedFile(DevConstants.NOTCH_2_SRG_SRG));
            task.setExceptorCfg(delayedFile(DevConstants.JOINED_EXC));
            task.setExceptorJson(delayedFile(DevConstants.EXC_JSON));
            task.addTransformerClean(delayedFile(DevConstants.FML_RESOURCES + "/fml_at.cfg"));
            task.setApplyMarkers(true);
            task.dependsOn("downloadMcpTools", "mergeJars", "genSrgs");
        });

        makeTask("decompile", DecompileTask.class, task -> {
            task.setInJar(delayedFile(DevConstants.JAR_SRG_FML));
            task.setOutJar(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.setFernFlower(delayedFile(Constants.FERNFLOWER));
            task.setPatch(delayedFile(DevConstants.MCP_PATCH_DIR));
            task.setAstyleConfig(delayedFile(DevConstants.ASTYLE_CFG));
            task.dependsOn("downloadMcpTools", "deobfuscateJar");
        });

        makeTask("remapCleanJar", RemapSourcesTask.class, task -> {
            task.setInJar(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.setOutJar(delayedFile(DevConstants.REMAPPED_CLEAN));
            task.setMethodsCsv(delayedFile(DevConstants.METHODS_CSV));
            task.setFieldsCsv(delayedFile(DevConstants.FIELDS_CSV));
            task.setParamsCsv(delayedFile(DevConstants.PARAMS_CSV));
            task.setDoesCache(false);
            task.setNoJavadocs();
            task.dependsOn("decompile");
        });

        makeTask("fmlPatchJar", ProcessSrcJarTask.class, task -> {
            task.setInJar(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.setOutJar(delayedFile(DevConstants.ZIP_PATCHED_FML));
            task.addStage("fml", delayedFile(DevConstants.FML_PATCH_DIR));
            task.setDoesCache(false);
            task.setMaxFuzz(2);
            task.dependsOn("decompile");
        });

        makeTask("remapDirtyJar", RemapSourcesTask.class, task -> {
            task.setInJar(delayedFile(DevConstants.ZIP_PATCHED_FML));
            task.setOutJar(delayedFile(DevConstants.REMAPPED_DIRTY));
            task.setMethodsCsv(delayedFile(DevConstants.METHODS_CSV));
            task.setFieldsCsv(delayedFile(DevConstants.FIELDS_CSV));
            task.setParamsCsv(delayedFile(DevConstants.PARAMS_CSV));
            task.setDoesCache(false);
            task.setNoJavadocs();
            task.dependsOn("fmlPatchJar");
        });
    }

    private void createSourceCopyTasks() {
        // COPY CLEAN STUFF
        makeTask("extractMcResources", ExtractTask.class, task -> {
            task.exclude(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(DevConstants.REMAPPED_CLEAN));
            task.into(delayedFile(DevConstants.ECLIPSE_CLEAN_RES));
            task.dependsOn("extractWorkspace", "remapCleanJar");
        });

        makeTask("copyStart", Copy.class, task -> {
            task.from(delayedFile("{FML_CONF_DIR}/patches"));
            task.include("Start.java");
            task.into(delayedFile(DevConstants.ECLIPSE_CLEAN_SRC));
            task.dependsOn("extractMcResources");
        });

        makeTask("extractMcSource", ExtractTask.class, task -> {
            task.include(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(DevConstants.REMAPPED_CLEAN));
            task.into(delayedFile(DevConstants.ECLIPSE_CLEAN_SRC));
            task.dependsOn("copyStart");
        });

        // COPY FML STUFF
        makeTask("extractFmlResources", ExtractTask.class, task -> {
            task.exclude(JAVA_FILES);
            task.from(delayedFile(DevConstants.REMAPPED_DIRTY));
            task.into(delayedFile(DevConstants.ECLIPSE_FML_RES));
            task.dependsOn("remapDirtyJar", "extractWorkspace");
        });

        makeTask("copyDeobfData", Copy.class, task -> {
            task.from(delayedFile(DevConstants.DEOBF_DATA));
            task.from(delayedFile(DevConstants.FML_VERSION));
            task.into(delayedFile(DevConstants.ECLIPSE_FML_RES));
            task.dependsOn("extractFmlResources", "compressDeobfData");
        });

        makeTask("extractFmlSources", ExtractTask.class, task -> {
            task.include(JAVA_FILES);
            task.exclude("cpw/**");
            task.exclude("net/minecraftforge/fml/**");
            task.from(delayedFile(DevConstants.REMAPPED_DIRTY));
            task.into(delayedFile(DevConstants.ECLIPSE_FML_SRC));
            task.dependsOn("copyDeobfData");
        });
    }

    private void createProjectTasks() {
        makeTask("generateProjectClean", GenDevProjectsTask.class, task -> {
            task.setTargetDir(delayedFile(DevConstants.ECLIPSE_CLEAN));
            task.setJson(delayedFile(DevConstants.JSON_DEV)); // Change to FmlConstants.JSON_BASE eventually, so that it's the base vanilla json

            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.setMappingChannel(delayedString("{MAPPING_CHANNEL}"));
            task.setMappingVersion(delayedString("{MAPPING_VERSION}"));

            task.dependsOn("extractNatives");
        });

        makeTask("generateProjectFML", GenDevProjectsTask.class, task -> {
            task.setJson(delayedFile(DevConstants.JSON_DEV));
            task.setTargetDir(delayedFile(DevConstants.ECLIPSE_FML));

            task.addSource(delayedFile(DevConstants.ECLIPSE_FML_SRC));
            task.addSource(delayedFile(DevConstants.FML_SOURCES));
            task.addTestSource(delayedFile(DevConstants.FML_TEST_SOURCES));

            task.addResource(delayedFile(DevConstants.ECLIPSE_FML_RES));
            task.addResource(delayedFile(DevConstants.FML_RESOURCES));
            task.addTestResource(delayedFile(DevConstants.FML_TEST_RES));

            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.setMappingChannel(delayedString("{MAPPING_CHANNEL}"));
            task.setMappingVersion(delayedString("{MAPPING_VERSION}"));

            task.dependsOn("extractNatives", "createVersionProperties");
        });

        makeTask("generateProjects", task -> task.dependsOn("generateProjectClean", "generateProjectFML"));
    }

    private void createEclipseTasks() {
        makeTask("eclipseClean", SubprojectTask.class, task -> {
            task.setBuildFile(delayedFile(DevConstants.ECLIPSE_CLEAN + "/build.gradle"));
            task.setTasks(EclipsePlugin.ECLIPSE_TASK_NAME);
            task.dependsOn("extractMcSource", "generateProjects");
        });

        makeTask("eclipseFML", SubprojectTask.class, task -> {
            task.setBuildFile(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"));
            task.setTasks(EclipsePlugin.ECLIPSE_TASK_NAME);
            task.dependsOn("extractFmlSources", "generateProjects");
        });

        makeTask(EclipsePlugin.ECLIPSE_TASK_NAME, task1 -> task1.dependsOn("eclipseClean", "eclipseFML"));
    }

    private void createMiscTasks() {
        DelayedFile rangeMap = delayedFile("{BUILD_DIR}/tmp/rangemap.txt");

        makeTask("extractRange", ExtractS2SRangeTask.class, task -> {
            task.setLibsFromProject(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"), CONFIG_COMPILE, true);
            task.addIn(delayedFile(DevConstants.ECLIPSE_FML_SRC));
            //task.addIn(delayedFile(DevConstants.FML_SOURCES));
            task.setExcOutput(delayedFile(DevConstants.EXC_MODIFIERS_DIRTY));
            task.setRangeMap(rangeMap);
        });

        makeTask("retroMapSources", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(DevConstants.ECLIPSE_FML_SRC));
            task.setOut(delayedFile(DevConstants.PATCH_DIRTY));
            task.addSrg(delayedFile(DevConstants.MCP_2_SRG_SRG));
            task.addExc(delayedFile(DevConstants.MCP_EXC));
            task.addExc(delayedFile(DevConstants.SRG_EXC)); // both EXCs just in case.
            task.setExcModifiers(delayedFile(EXC_MODIFIERS_DIRTY));
            task.setRangeMap(rangeMap);
            task.dependsOn("genSrgs", "extractRange");

            // find all the exc & srg files in the resources.
            for (File f : project.fileTree(delayedFile(DevConstants.FML_RESOURCES).call()).getFiles()) {
                if (f.getPath().endsWith(".exc"))
                    task.addExc(f);
                else if (f.getPath().endsWith(".srg"))
                    task.addSrg(f);
            }
        });

        makeTask("genPatches", GeneratePatches.class, task -> {
            task.setPatchDir(delayedFile(DevConstants.FML_PATCH_DIR));
            task.setOriginal(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.setChanged(delayedFile(DevConstants.PATCH_DIRTY));
            task.setOriginalPrefix("../src-base/minecraft");
            task.setChangedPrefix("../src-work/minecraft");
            task.setGroup("FML");
            task.dependsOn("retroMapSources");
        });

        makeTask("cleanFml", Delete.class, task -> {
            task.delete("eclipse");
            task.setGroup("Clean");
        });

        makeTask("obfuscateJar", ObfuscateTask.class, task -> {
            task.setSrg(delayedFile(DevConstants.MCP_2_NOTCH_SRG));
            task.setExc(delayedFile(DevConstants.SRG_EXC));
            task.setReverse(false);
            task.setPreFFJar(delayedFile(DevConstants.JAR_SRG_FML));
            task.setOutJar(delayedFile(DevConstants.REOBF_TMP));
            task.setBuildFile(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"));
            task.setMethodsCsv(delayedFile(DevConstants.METHODS_CSV));
            task.setFieldsCsv(delayedFile(DevConstants.FIELDS_CSV));
            task.dependsOn("generateProjects", "extractFmlSources", "genSrgs");
        });

        makeTask("genBinPatches", GenBinaryPatches.class, task -> {
            task.setCleanClient(delayedFile(Constants.JAR_CLIENT_FRESH));
            task.setCleanServer(delayedFile(Constants.JAR_SERVER_FRESH));
            task.setCleanMerged(delayedFile(Constants.JAR_MERGED));
            task.setDirtyJar(delayedFile(DevConstants.REOBF_TMP));
            task.setDeobfDataLzma(delayedFile(DevConstants.DEOBF_DATA));
            task.setOutJar(delayedFile(DevConstants.BINPATCH_TMP));
            task.setSrg(delayedFile(DevConstants.NOTCH_2_SRG_SRG));
            task.addPatchList(delayedFileTree(DevConstants.FML_PATCH_DIR));
            task.dependsOn("obfuscateJar", "compressDeobfData");
        });

        makeTask("createVersionProperties", FMLVersionPropTask.class, task -> {
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.setOutputFile(delayedFile(DevConstants.FML_VERSION));
        });
    }

    private void createPackageTasks() {
        makeTask("getLocalizations", CrowdinDownloadTask.class, task -> {
            task.setOutput(delayedFile(CROWDIN_ZIP));
            task.setProjectId(CROWDIN_FORGEID);
            task.setExtract(false);
        });

        makeTask("createChangelog", ChangelogTask.class, task -> {
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.setServerRoot(delayedString("{JENKINS_SERVER}"));
            task.setJobName(delayedString("{JENKINS_JOB}"));
            task.setAuthName(delayedString("{JENKINS_AUTH_NAME}"));
            task.setAuthPassword(delayedString("{JENKINS_AUTH_PASSWORD}"));
            task.setTargetBuild(delayedString("{BUILD_NUM}"));
            task.setOutput(delayedFile(DevConstants.CHANGELOG));
        });

        final DelayedJar uni = makeTask("packageUniversal", DelayedJar.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "universal");
            task.getInputs().file(delayedFile(DevConstants.JSON_REL));
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.from(delayedZipTree(DevConstants.BINPATCH_TMP));
            task.from(delayedFileTree(DevConstants.FML_RESOURCES));
            task.from(delayedZipTree(DevConstants.CROWDIN_ZIP));
            task.from(delayedFile(DevConstants.FML_VERSION));
            task.from(delayedFile(DevConstants.FML_LICENSE));
            task.from(delayedFile(DevConstants.FML_CREDITS));
            task.from(delayedFile(DevConstants.DEOBF_DATA));
            task.from(delayedFile(DevConstants.CHANGELOG));
            task.exclude("devbinpatches.pack.lzma");
            task.setIncludeEmptyDirs(false);
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.setManifest(new Action<Manifest>() {
                @Override
                public void execute(Manifest manifest) {
                    manifest.getAttributes().put("Main-Class", delayedString("{MAIN_CLASS}").call());
                    manifest.getAttributes().put("TweakClass", delayedString("{FML_TWEAK_CLASS}").call());
                    manifest.getAttributes().put("Class-Path", getServerClassPath(delayedFile(DevConstants.JSON_REL).call()));
                }
            });
            task.dependsOn("genBinPatches", "getLocalizations", "createChangelog", "createVersionProperties");
        }).get();

        project.getArtifacts().add("archives", uni);

        makeTask("generateInstallJson", FileFilterTask.class, task -> {
            task.setInputFile(delayedFile(DevConstants.JSON_REL));
            task.setOutputFile(delayedFile(DevConstants.INSTALL_PROFILE));
            task.addReplacement("@minecraft_version@", delayedString("{MC_VERSION}"));
            task.addReplacement("@version@", delayedString("{VERSION}"));
            task.addReplacement("@project@", delayedString("FML"));
            task.addReplacement("@artifact@", delayedString("cpw.mods:fml:{MC_VERSION_SAFE}-{VERSION}"));
            task.addReplacement("@universal_jar@", (Supplier<String>) () -> ArchiveTaskHelper.getArchiveName((AbstractArchiveTask) task.dependsOn("packageUniversal")));
            task.addReplacement("@timestamp@", (Supplier<String>) () -> (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")).format(new Date()));
        });

        Zip inst = makeTask("packageInstaller", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "installer");
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("packageUniversal")));
            task.from(delayedFile(DevConstants.INSTALL_PROFILE));
            task.from(delayedFile(DevConstants.CHANGELOG));
            task.from(delayedFile(DevConstants.FML_LICENSE));
            task.from(delayedFile(DevConstants.FML_CREDITS));
            task.from(delayedFile(DevConstants.FML_LOGO));
            task.from(delayedZipTree(DevConstants.INSTALLER_BASE), new CopyInto("/", "!*.json", "!*.png"));
            task.dependsOn("downloadBaseInstaller", "generateInstallJson");
            ArchiveTaskHelper.setExtension(task, "jar");
        }).get();

        project.getArtifacts().add("archives", inst);

        makeTask("zipPatches", Zip.class, task -> {
            task.from(delayedFile(DevConstants.FML_PATCH_DIR));
            ArchiveTaskHelper.setArchiveName(task, "fmlpatches.zip");
        });

        makeTask("jarClasses", Zip.class, task -> {
            task.from(delayedZipTree(DevConstants.BINPATCH_TMP), new CopyInto("", "**/*.class"));
            ArchiveTaskHelper.setArchiveName(task, "binaries.jar");
        });

        makeTask("userDevExtractRange", ExtractS2SRangeTask.class, task -> {
            task.setLibsFromProject(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"), CONFIG_COMPILE, true);
            task.addIn(delayedFile(DevConstants.FML_SOURCES));
            task.setRangeMap(delayedFile(DevConstants.USERDEV_RANGEMAP));
            task.dependsOn("generateProjects", "extractFmlSources");
        });

        makeTask("userDevSrgSrc", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(DevConstants.FML_SOURCES));
            task.setOut(delayedFile(DevConstants.USERDEV_SRG_SRC));
            task.addSrg(delayedFile(DevConstants.MCP_2_SRG_SRG));
            task.addExc(delayedFile(DevConstants.JOINED_EXC));
            task.setRangeMap(delayedFile(DevConstants.USERDEV_RANGEMAP));
            task.dependsOn("genSrgs", "userDevExtractRange");
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE); //Fucking caching.

            // find all the exc & srg files in the resources.
            for (File f : project.fileTree(delayedFile(DevConstants.FML_RESOURCES).call()).getFiles()) {
                if (f.getPath().endsWith(".exc"))
                    task.addExc(f);
                else if (f.getPath().endsWith(".srg"))
                    task.addSrg(f);
            }
        });

        Zip userDev = makeTask("packageUserDev", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task,"userdev");
            task.from(delayedFile(DevConstants.JSON_DEV));
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("zipPatches")));
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("jarClasses")));
            task.from(delayedFile(DevConstants.CHANGELOG));
            task.from(delayedZipTree(DevConstants.BINPATCH_TMP), new CopyInto("", "devbinpatches.pack.lzma"));
            task.from(delayedFileTree("{FML_DIR}/src/main/resources"), new CopyInto("src/main/resources"));
            task.from(delayedZipTree(DevConstants.CROWDIN_ZIP), new CopyInto("src/main/resources"));
            task.from(delayedFile(DevConstants.FML_VERSION), new CopyInto("src/main/resources"));
            task.from(delayedZipTree(DevConstants.USERDEV_SRG_SRC), new CopyInto("src/main/java"));
            task.from(delayedFile(DevConstants.DEOBF_DATA), new CopyInto("src/main/resources/"));
            task.from(delayedFile(DevConstants.MERGE_CFG), new CopyInto("conf"));
            task.from(delayedFileTree("{FML_CONF_DIR}"), new CopyInto("conf", "astyle.cfg", "exceptor.json", "*.csv", "!packages.csv"));
            task.from(delayedFile(DevConstants.NOTCH_2_SRG_SRG), new CopyInto("conf"));
            task.from(delayedFile(DevConstants.SRG_EXC), new CopyInto("conf"));
            task.from(delayedFileTree("{FML_CONF_DIR}/patches"), new CopyInto("conf"));
            task.rename(".+-dev\\.json", "dev.json");
            task.rename(".+?\\.srg", "packaged.srg");
            task.rename(".+?\\.exc", "packaged.exc");
            task.setIncludeEmptyDirs(false);
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.dependsOn("packageUniversal", "getLocalizations", "createVersionProperties", "userDevSrgSrc");
            ArchiveTaskHelper.setExtension(task,"jar");
        }).get();

        project.getArtifacts().add("archives", userDev);

        Zip src = makeTask("packageSrc", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "src");
            task.from(delayedFile(DevConstants.CHANGELOG));
            task.from(delayedFile(DevConstants.FML_LICENSE));
            task.from(delayedFile(DevConstants.FML_CREDITS));
            task.from(delayedFile("{FML_DIR}/install"), new CopyInto(null, "!*.gradle"));
            task.from(delayedFile("{FML_DIR}/install"), (new CopyInto(null, "*.gradle"))
                    .addExpand("version", delayedString("{MC_VERSION_SAFE}-{VERSION}"))
                    .addExpand("mappings", delayedString("{MAPPING_CHANNEL_DOC}_{MAPPING_VERSION}"))
                    .addExpand("name", "fml"));
            task.from(delayedFile("{FML_DIR}/gradlew"));
            task.from(delayedFile("{FML_DIR}/gradlew.bat"));
            task.from(delayedFile("{FML_DIR}/gradle/wrapper"), new CopyInto("gradle/wrapper"));
            task.rename(".+?\\.gradle", "build.gradle");
            task.dependsOn("createChangelog");
            ArchiveTaskHelper.setExtension(task, "zip");
        }).get();

        project.getArtifacts().add("archives", src);
    }

    public static String getVersionFromGit(Project project) {
        return getVersionFromGit(project, project.getProjectDir());
    }

    public static String getVersionFromGit(Project project, File workDir) {
        if (project == null) {
            project = BasePlugin.getProject(null, null);
        }

        String fullVersion = runGit(project, workDir, "describe", "--long", "--match=[^(jenkins)]*");
        if (fullVersion == null) return null;
        fullVersion = fullVersion.replace('-', '.').replaceAll("[^0-9.]", ""); //Normalize splitter, and remove non-numbers
        String[] pts = fullVersion.split("\\.");

        String major = pts[0];
        String minor = pts[1];
        String revision = pts[2];
        String build = "0";

        if (System.getenv().containsKey("BUILD_NUMBER")) {
            build = System.getenv("BUILD_NUMBER");
        }

        String branch;
        if (!System.getenv().containsKey("GIT_BRANCH")) {
            branch = runGit(project, workDir, "rev-parse", "--abbrev-ref", "HEAD");
            if (branch == null) return null;
        } else {
            branch = System.getenv("GIT_BRANCH");
            branch = branch.substring(branch.lastIndexOf('/') + 1);
        }

        if (branch != null && (branch.equals("master") || branch.equals("HEAD"))) {
            branch = null;
        }

        StringBuilder out = new StringBuilder();
        out.append(DelayedBase.resolve("{MC_VERSION_SAFE}", project)).append('-'); // Somehow configure this?
        out.append(major).append('.').append(minor).append('.').append(revision).append('.').append(build);
        if (branch != null) {
            out.append('-').append(branch);
        }

        return out.toString();
    }

    @Override
    public void afterEvaluate() {
        super.afterEvaluate();

        this.<SubprojectTask>configureTask("eclipseClean", task -> {
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getCleanProject());
        });

        this.<SubprojectTask>configureTask("eclipseFML", task -> {
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getCleanProject());
        });
    }
}