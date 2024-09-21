package net.minecraftforge.gradle.tasks.dev;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.minecraftforge.gradle.ArchiveTaskHelper;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.dev.FmlDevPlugin;
import net.minecraftforge.gradle.extrastuff.ReobfExceptor;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.compile.AbstractCompile;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedList;

public class ObfuscateTask extends DefaultTask {
    @OutputFile
    private DelayedFile outJar;
    @InputFile
    private DelayedFile preFFJar;
    @InputFile
    private DelayedFile srg;
    @InputFile
    private DelayedFile exc;
    @Input
    private boolean reverse;
    @InputFile
    private DelayedFile buildFile;
    private final LinkedList<Action<Project>> configureProject = new LinkedList<>();
    @InputFile
    private DelayedFile methodsCsv;
    @InputFile
    private DelayedFile fieldsCsv;
    @Input
    private String subTask = JavaPlugin.JAR_TASK_NAME;
    @Input
    private LinkedList<String> extraSrg = new LinkedList<>();

    @TaskAction
    public void doTask() throws IOException {
        getLogger().debug("Building child project model...");
        Project childProj = FmlDevPlugin.getProject(getBuildFile(), getProject());
        for (Action<Project> act : configureProject) {
            if (act != null)
                act.execute(childProj);
        }

        AbstractCompile compileTask = (AbstractCompile) childProj.getTasks().getByName(JavaPlugin.COMPILE_JAVA_TASK_NAME);
        AbstractArchiveTask jarTask = (AbstractArchiveTask) childProj.getTasks().getByName(subTask);

        // executing jar task
        getLogger().debug("Executing child {} task...", subTask);
        Constants.executeTask(jarTask, getLogger());

        File inJar = ArchiveTaskHelper.getArchivePath(jarTask);

        File srg = getSrg();

        if (getExc() != null) {
            ReobfExceptor exceptor = new ReobfExceptor();
            exceptor.toReobfJar = inJar;
            exceptor.deobfJar = getPreFFJar();
            exceptor.excConfig = getExc();
            exceptor.fieldCSV = getFieldsCsv();
            exceptor.methodCSV = getMethodsCsv();

            File outSrg = new File(this.getTemporaryDir(), "reobf_cls.srg");

            exceptor.doFirstThings();
            exceptor.buildSrg(srg, outSrg);

            srg = outSrg;
        }

        // append SRG
        Files.write(srg.toPath(), extraSrg, StandardOpenOption.APPEND);

        getLogger().debug("Obfuscating jar...");
        obfuscate(inJar, compileTask.getClasspath(), srg);
    }

    private void obfuscate(File inJar, FileCollection classpath, File srg) throws IOException {
        // load mapping
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(Files.newBufferedReader(srg.toPath(), Charset.defaultCharset()), null, null, reverse);

        // make remapper
        JarRemapper remapper = new JarRemapper(null, mapping);

        // load jar
        Jar input = Jar.init(inJar);

        // ensure that inheritance provider is used
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));

        if (classpath != null)
            inheritanceProviders.add(new ClassLoaderProvider(new URLClassLoader(Constants.toUrls(classpath))));

        mapping.setFallbackInheritanceProvider(inheritanceProviders);

        File out = getOutJar();
        if (!out.getParentFile().exists()) { //Needed because SS doesn't create it.
            out.getParentFile().mkdirs();
        }

        // remap jar
        remapper.remapJar(input, getOutJar());
    }

    public File getOutJar() {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

    public File getPreFFJar() {
        return preFFJar.call();
    }

    public void setPreFFJar(DelayedFile preFFJar) {
        this.preFFJar = preFFJar;
    }

    public File getSrg() {
        return srg.call();
    }

    public void setSrg(DelayedFile srg) {
        this.srg = srg;
    }

    public File getExc() {
        return exc.call();
    }

    public void setExc(DelayedFile exc) {
        this.exc = exc;
    }

    public boolean isReverse() {
        return reverse;
    }

    public void setReverse(boolean reverse) {
        this.reverse = reverse;
    }

    public File getBuildFile() {
        return buildFile.call();
    }

    public void setBuildFile(DelayedFile buildFile) {
        this.buildFile = buildFile;
    }


    public File getMethodsCsv() {
        return methodsCsv.call();
    }

    public void setMethodsCsv(DelayedFile methodsCsv) {
        this.methodsCsv = methodsCsv;
    }

    public File getFieldsCsv() {
        return fieldsCsv.call();
    }

    public void setFieldsCsv(DelayedFile fieldsCsv) {
        this.fieldsCsv = fieldsCsv;
    }

    public void configureProject(Action<Project> action) {
        configureProject.add(action);
    }

    public LinkedList<String> getExtraSrg() {
        return extraSrg;
    }

    public void setExtraSrg(LinkedList<String> extraSrg) {
        this.extraSrg = extraSrg;
    }

    public String getSubTask() {
        return subTask;
    }

    public void setSubTask(String subTask) {
        this.subTask = subTask;
    }
}